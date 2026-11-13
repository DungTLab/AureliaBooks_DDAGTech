package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.VNPayIpnResponse;
import com.ddagtech.aureliabooks.dto.response.VNPayPaymentResultView;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.JdbcOrderVoucherReleaseAdapter;
import com.ddagtech.aureliabooks.service.impl.VNPayServiceImpl;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit test suite for {@link VNPayServiceImpl} (UC11.1 - Pay via VNPay).
 * Verifies V2.4 invariants and F03–F08 security/consistency requirements:
 * - F03: Fail closed when VNPay credentials are missing or blank; no default secrets.
 * - F04: Enforce ROLE_CUSTOMER and strict order ownership before generating payment URLs.
 * - F05: Classify gateway outcomes into VERIFIED_SUCCESS, VERIFIED_TERMINAL_FAILURE, and INDETERMINATE.
 * - F06: Propagate voucher release SQL errors so the enclosing transaction rolls back atomically.
 * - F07: Anchor the 15-minute payment deadline to order.createdAt without extending on retry.
 * - F08: Context-aware Return URL (SCR-ST-13) distinguishing PAID, PENDING_PAYMENT, and CANCELLED late success.
 */
@ExtendWith(MockitoExtension.class)
class VNPayServiceTest {

    private static final String TEST_SECRET = "UNIT_TEST_HMAC_SECRET_KEY_0123456789ABCDEF";
    private static final LocalDateTime BASE_ORDER_CREATED_AT = LocalDateTime.of(2026, 10, 24, 10, 0, 0);

    @Spy
    private VNPayConfig vnPayConfig = new VNPayConfig();

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StockMutationService stockMutationService;

    @Mock
    private OrderVoucherReleasePort orderVoucherReleasePort;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private VNPayServiceImpl vnPayService;

    private User ownerCustomer;

    @BeforeEach
    void setUp() {
        vnPayConfig.setTmnCode("AURELIA01");
        vnPayConfig.setHashSecret(TEST_SECRET);
        vnPayConfig.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        vnPayConfig.setReturnUrl("http://localhost:8080/payment/vnpay-return");
        vnPayConfig.setApiUrl("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
        vnPayConfig.setVersion("2.1.0");
        vnPayConfig.setCommand("pay");
        vnPayConfig.setOrderType("other");
        vnPayConfig.setExpireMinutes(15);

        ownerCustomer = User.builder()
                .id(1L)
                .email("owner@aureliabook.vn")
                .phone("0901234567")
                .fullName("Owner Customer")
                .build();

        vnPayService.setClock(fixedVietnamClock(BASE_ORDER_CREATED_AT.plusMinutes(2)));
        authenticateAsCustomer(ownerCustomer);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // F03: Fail-Closed Configuration & Signature Verification
    // =========================================================================

    @Test
    @DisplayName("F03: newly instantiated VNPayConfig should have no hardcoded fallback tmnCode or hashSecret")
    void defaultVNPayConfig_HasNoHardcodedFallbackSecrets() {
        VNPayConfig freshConfig = new VNPayConfig();
        assertThat(freshConfig.getTmnCode()).isNull();
        assertThat(freshConfig.getHashSecret()).isNull();
        assertThat(freshConfig.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("F03: createPaymentUrl() should fail closed when tmnCode or hashSecret is blank")
    void createPaymentUrl_MissingConfig_FailsClosed() {
        vnPayConfig.setHashSecret("   ");
        assertThat(vnPayConfig.isConfigured()).isFalse();

        assertThatThrownBy(() -> vnPayService.createPaymentUrl(1L, 10L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);

        verify(orderRepository, never()).findByIdWithLock(anyLong());
    }

    @Test
    @DisplayName("F03: processIpnWebhook() should return RspCode 97 when config is missing even if payload is signed with legacy fallback secret")
    void processIpnWebhook_MissingConfigWithLegacySecretPayload_Returns97WithoutTouchingDb() {
        vnPayConfig.setTmnCode("");
        vnPayConfig.setHashSecret("");

        Map<String, String> forgedWithLegacyDefault = new TreeMap<>();
        forgedWithLegacyDefault.put("vnp_Amount", "25000000");
        forgedWithLegacyDefault.put("vnp_ResponseCode", "00");
        forgedWithLegacyDefault.put("vnp_TmnCode", "AURELIA01");
        forgedWithLegacyDefault.put("vnp_TransactionStatus", "00");
        forgedWithLegacyDefault.put("vnp_TxnRef", "ORD-20261016-001");
        String hashData = VNPayUtil.buildHashData(forgedWithLegacyDefault);
        forgedWithLegacyDefault.put(
                "vnp_SecureHash",
                VNPayUtil.hmacSHA512("SECRETKEY1234567890ABCDEF12345678", hashData)
        );

        VNPayIpnResponse response = vnPayService.processIpnWebhook(forgedWithLegacyDefault, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("97");
        assertThat(response.message()).isEqualTo("Invalid Checksum");
        verify(orderRepository, never()).findByVnpayTxnRefWithLock(any());
    }

    @Test
    @DisplayName("F03: processIpnWebhook() should return RspCode 97 when vnp_TmnCode does not match configured terminal code")
    void processIpnWebhook_MismatchedTmnCode_Returns97() {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Amount", "25000000");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TmnCode", "OTHER_TMN");
        params.put("vnp_TransactionStatus", "00");
        params.put("vnp_TxnRef", "ORD-20261016-001");
        String hashData = VNPayUtil.buildHashData(params);
        params.put("vnp_SecureHash", VNPayUtil.hmacSHA512(TEST_SECRET, hashData));

        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("97");
        verify(orderRepository, never()).findByVnpayTxnRefWithLock(any());
    }

    // =========================================================================
    // F04: Customer Authentication & Order Ownership
    // =========================================================================

    @Test
    @DisplayName("F04: createPaymentUrl() should generate signed VNPay URL when called by the order owner within 15-minute window")
    void createPaymentUrl_ValidPendingOrderOwnedByCustomer_GeneratesSignedUrl() {
        Order order = Order.builder()
                .id(10L)
                .user(ownerCustomer)
                .orderCode("ORD-20261016-001")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();
        order.setCreatedAt(BASE_ORDER_CREATED_AT);

        when(orderRepository.findByIdWithLock(10L)).thenReturn(Optional.of(order));

        String url = vnPayService.createPaymentUrl(ownerCustomer.getId(), 10L, "127.0.0.1");

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(url).contains("vnp_Amount=25000000");
        assertThat(url).contains("vnp_TxnRef=ORD-20261016-001");
        assertThat(url).contains("vnp_ExpireDate=20261024101500");
        assertThat(url).contains("vnp_SecureHash=");
        assertThat(order.getVnpayTxnRef()).isEqualTo("ORD-20261016-001");
    }

    @Test
    @DisplayName("F04: createPaymentUrl() should reject another customer attempting to initiate payment for someone else's order")
    void createPaymentUrl_DifferentCustomer_ThrowsUnauthorizedWithoutMutatingOrder() {
        User otherCustomer = User.builder()
                .id(2L)
                .email("intruder@aureliabook.vn")
                .fullName("Other Customer")
                .build();
        authenticateAsCustomer(otherCustomer);

        Order order = Order.builder()
                .id(10L)
                .user(ownerCustomer)
                .orderCode("ORD-20261016-001")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();
        order.setCreatedAt(BASE_ORDER_CREATED_AT);

        when(orderRepository.findByIdWithLock(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> vnPayService.createPaymentUrl(otherCustomer.getId(), 10L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        assertThat(order.getVnpayTxnRef()).isNull();
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("F04: createPaymentUrl() should reject non-customer roles (e.g. ROLE_SALE_STAFF or ROLE_ADMIN)")
    void createPaymentUrl_NonCustomerRole_ThrowsUnauthorized() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "staff@aureliabook.vn",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_SALE_STAFF"))
                )
        );

        Order order = Order.builder()
                .id(10L)
                .user(ownerCustomer)
                .orderCode("ORD-20261016-001")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();
        order.setCreatedAt(BASE_ORDER_CREATED_AT);

        when(orderRepository.findByIdWithLock(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> vnPayService.createPaymentUrl(10L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        assertThat(order.getVnpayTxnRef()).isNull();
    }

    @Test
    @DisplayName("F04: createPaymentUrl() should reject unauthenticated callers")
    void createPaymentUrl_Unauthenticated_ThrowsUnauthenticated() {
        SecurityContextHolder.clearContext();

        Order order = Order.builder()
                .id(10L)
                .user(ownerCustomer)
                .orderCode("ORD-20261016-001")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();
        order.setCreatedAt(BASE_ORDER_CREATED_AT);

        when(orderRepository.findByIdWithLock(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> vnPayService.createPaymentUrl(10L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    // =========================================================================
    // F07: Fixed 15-Minute Payment Window Anchored to order.createdAt
    // =========================================================================

    @Test
    @DisplayName("F07: repeated createPaymentUrl() retries before the 15-minute deadline must keep vnp_ExpireDate anchored to order.createdAt + 15m")
    void createPaymentUrl_RepeatedRetryBeforeDeadline_DoesNotExtendExpireDate() {
        Order order = Order.builder()
                .id(10L)
                .user(ownerCustomer)
                .orderCode("ORD-20261016-001")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();
        order.setCreatedAt(BASE_ORDER_CREATED_AT);

        when(orderRepository.findByIdWithLock(10L)).thenReturn(Optional.of(order));

        vnPayService.setClock(fixedVietnamClock(BASE_ORDER_CREATED_AT.plusMinutes(3)));
        String firstUrl = vnPayService.createPaymentUrl(ownerCustomer.getId(), 10L, "127.0.0.1");

        vnPayService.setClock(fixedVietnamClock(BASE_ORDER_CREATED_AT.plusMinutes(12)));
        String retryUrl = vnPayService.createPaymentUrl(ownerCustomer.getId(), 10L, "127.0.0.1");

        assertThat(firstUrl).contains("vnp_TxnRef=ORD-20261016-001");
        assertThat(firstUrl).contains("vnp_CreateDate=20261024100300");
        assertThat(firstUrl).contains("vnp_ExpireDate=20261024101500");
        assertThat(retryUrl).contains("vnp_TxnRef=ORD-20261016-001-R101200");
        assertThat(retryUrl).contains("vnp_CreateDate=20261024101200");
        assertThat(retryUrl).contains("vnp_ExpireDate=20261024101500");
        assertThat(order.getVnpayTxnRef()).isEqualTo("ORD-20261016-001-R101200");
    }

    @Test
    @DisplayName("F07: createPaymentUrl() at or after the 15-minute deadline must reject URL creation without auto-cancelling the order")
    void createPaymentUrl_AtOrAfter15MinuteDeadline_ThrowsExceptionWithoutCancellingOrder() {
        Order order = Order.builder()
                .id(10L)
                .user(ownerCustomer)
                .orderCode("ORD-20261016-001")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();
        order.setCreatedAt(BASE_ORDER_CREATED_AT);

        when(orderRepository.findByIdWithLock(10L)).thenReturn(Optional.of(order));

        // Exact 15-minute boundary
        vnPayService.setClock(fixedVietnamClock(BASE_ORDER_CREATED_AT.plusMinutes(15)));
        assertThatThrownBy(() -> vnPayService.createPaymentUrl(ownerCustomer.getId(), 10L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);

        // After 15-minute boundary
        vnPayService.setClock(fixedVietnamClock(BASE_ORDER_CREATED_AT.plusMinutes(20)));
        assertThatThrownBy(() -> vnPayService.createPaymentUrl(ownerCustomer.getId(), 10L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);

        // Order must remain PENDING_PAYMENT for CRON-01 reconciliation
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_PAYMENT);
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
    }

    @Test
    @DisplayName("createPaymentUrl() should reject order whose stock was not deducted at order creation (BR-04-02)")
    void createPaymentUrl_StockNotDeducted_ThrowsException() {
        Order order = Order.builder()
                .id(11L)
                .user(ownerCustomer)
                .orderCode("ORD-20261016-002")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("150000.00"))
                .stockDeductedAt(null)
                .build();
        order.setCreatedAt(BASE_ORDER_CREATED_AT);

        when(orderRepository.findByIdWithLock(11L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> vnPayService.createPaymentUrl(ownerCustomer.getId(), 11L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ORDER_STOCK_NOT_DEDUCTED);
    }

    // =========================================================================
    // F07 Addendum: UTC JVM Clock Consistency
    // =========================================================================

    @Test
    @DisplayName("F07: when JVM runs in UTC, createPaymentUrl() converts UTC createdAt into GMT+7 VNPay timestamps without premature expiration")
    void createPaymentUrl_UtcJvmClock_ConvertsUtcCreatedAtToVietnamGmt7Timestamps() {
        // Real instant: 2026-10-24T17:30:00Z = 2026-10-25 00:30:00 Asia/Ho_Chi_Minh (crosses midnight in GMT+7)
        LocalDateTime utcCreatedAt = LocalDateTime.of(2026, 10, 24, 17, 30, 0);
        Order order = Order.builder()
                .id(12L)
                .user(ownerCustomer)
                .orderCode("ORD-UTC-12")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(utcCreatedAt)
                .build();
        order.setCreatedAt(utcCreatedAt);

        when(orderRepository.findByIdWithLock(12L)).thenReturn(Optional.of(order));

        // Two minutes later in UTC JVM (17:32:00Z = 00:32:00+07 on 2026-10-25)
        vnPayService.setClock(Clock.fixed(Instant.parse("2026-10-24T17:32:00Z"), ZoneOffset.UTC));
        String payUrl = vnPayService.createPaymentUrl(ownerCustomer.getId(), 12L, "127.0.0.1");

        assertThat(payUrl).contains("vnp_CreateDate=20261025003200");
        assertThat(payUrl).contains("vnp_ExpireDate=20261025004500");

        // Exact 15-minute expiration in UTC (17:45:00Z = 00:45:00+07) must be rejected
        vnPayService.setClock(Clock.fixed(Instant.parse("2026-10-24T17:45:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> vnPayService.createPaymentUrl(ownerCustomer.getId(), 12L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);
    }

    // =========================================================================
    // F05: IPN Outcome Classification (VERIFIED_SUCCESS, VERIFIED_TERMINAL_FAILURE, INDETERMINATE)
    // =========================================================================

    @Test
    @DisplayName("processIpnWebhook() should reject forged checksum with RspCode 97 without touching database")
    void processIpnWebhook_InvalidSignature_Returns97() {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_TxnRef", "ORD-20261016-001");
        params.put("vnp_Amount", "25000000");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TmnCode", "AURELIA01");
        params.put("vnp_SecureHash", "deadbeef0000");

        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("97");
        assertThat(response.message()).isEqualTo("Invalid Checksum");
        verify(orderRepository, never()).findByVnpayTxnRefWithLock(any());
        verify(stockMutationService, never()).deductOrder(anyLong());
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
    }

    @Test
    @DisplayName("processIpnWebhook() should return RspCode 01 when order does not exist")
    void processIpnWebhook_OrderNotFound_Returns01() {
        Map<String, String> params = signedParams("ORD-UNKNOWN", "25000000", "00", "00");
        when(orderRepository.findByVnpayTxnRefWithLock("ORD-UNKNOWN")).thenReturn(Optional.empty());

        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("01");
        assertThat(response.message()).isEqualTo("Order not Found");
    }

    @Test
    @DisplayName("processIpnWebhook() should return RspCode 04 when vnp_Amount does not match order finalTotalAmount * 100")
    void processIpnWebhook_AmountMismatch_Returns04() {
        Order order = Order.builder()
                .id(20L)
                .orderCode("ORD-20261016-020")
                .vnpayTxnRef("ORD-20261016-020")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-020")).thenReturn(Optional.of(order));

        Map<String, String> params = signedParams("ORD-20261016-020", "10000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("04");
        assertThat(response.message()).isEqualTo("Invalid Amount");
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("processIpnWebhook() on verified success (00/00) should transition to PAID & PENDING_CONFIRMATION WITHOUT deducting stock again (BR-04-02)")
    void processIpnWebhook_VerifiedSuccess_UpdatesOrderAndPaymentWithoutDuplicateStockDeduction() {
        User customer = User.builder().id(5L).fullName("Nguyen Van A").build();
        Order order = Order.builder()
                .id(30L)
                .user(customer)
                .orderCode("ORD-20261016-030")
                .vnpayTxnRef("ORD-20261016-030")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("320000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-030")).thenReturn(Optional.of(order));

        Map<String, String> params = signedParams("ORD-20261016-030", "32000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("00");
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        assertThat(order.getVnpayBankCode()).isEqualTo("NCB");
        assertThat(order.getVnpayPayDate()).isEqualTo("20261016103000");
        assertThat(order.getStockDeductedAt()).isEqualTo(BASE_ORDER_CREATED_AT);

        verify(stockMutationService, never()).deductOrder(anyLong());
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
        verify(orderRepository).save(order);
        verify(auditLogService).record(eq(5L), eq("VNPAY_PAYMENT_SUCCESS"), eq("orders"), eq(30L), any(), eq("203.0.113.10"));
    }

    @Test
    @DisplayName("F05: processIpnWebhook() on verified terminal failure (24/02) should cancel order, restore stock once, and release voucher")
    void processIpnWebhook_VerifiedFailure_CancelsOrderRestoresStockOnceAndReleasesVoucher() {
        User customer = User.builder().id(7L).fullName("Tran Thi B").build();
        Order order = Order.builder()
                .id(40L)
                .user(customer)
                .orderCode("ORD-20261016-040")
                .vnpayTxnRef("ORD-20261016-040")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-040")).thenReturn(Optional.of(order));

        Map<String, String> params = signedParams("ORD-20261016-040", "18000000", "24", "02");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("00");
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.UNPAID);
        assertThat(order.getCancelReason()).contains("24");

        verify(stockMutationService).restoreCancelledOrder(40L, 7L);
        verify(orderVoucherReleasePort).releaseOrderVoucherIfPresent(40L);
        verify(auditLogService).record(eq(7L), eq("VNPAY_PAYMENT_FAILED_CANCELLED"), eq("orders"), eq(40L), any(), eq("203.0.113.10"));
    }

    @Test
    @DisplayName("F05: processIpnWebhook() with incomplete transaction status (00/01) or unknown status (99/99) must return RspCode 99 and NOT cancel or restock")
    void processIpnWebhook_IncompleteOrUnknownStatus_Returns99WithoutCancellingOrRestocking() {
        User customer = User.builder().id(7L).fullName("Tran Thi B").build();
        Order order = Order.builder()
                .id(41L)
                .user(customer)
                .orderCode("ORD-20261016-041")
                .vnpayTxnRef("ORD-20261016-041")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-041")).thenReturn(Optional.of(order));

        // Case 1: vnp_ResponseCode=00, vnp_TransactionStatus=01 (incomplete)
        VNPayIpnResponse incompleteRes = vnPayService.processIpnWebhook(
                signedParams("ORD-20261016-041", "18000000", "00", "01"),
                "203.0.113.10"
        );
        assertThat(incompleteRes.rspCode()).isEqualTo("99");

        // Case 2: vnp_ResponseCode=99, vnp_TransactionStatus=99 (indeterminate error)
        VNPayIpnResponse unknownRes = vnPayService.processIpnWebhook(
                signedParams("ORD-20261016-041", "18000000", "99", "99"),
                "203.0.113.10"
        );
        assertThat(unknownRes.rspCode()).isEqualTo("99");

        // Case 3: missing vnp_TransactionStatus with vnp_ResponseCode=00 (must NOT default to 00)
        VNPayIpnResponse missingStatusRes = vnPayService.processIpnWebhook(
                signedParams("ORD-20261016-041", "18000000", "00", null),
                "203.0.113.10"
        );
        assertThat(missingStatusRes.rspCode()).isEqualTo("99");

        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_PAYMENT);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.UNPAID);
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
        verify(orderVoucherReleasePort, never()).releaseOrderVoucherIfPresent(anyLong());
    }

    @Test
    @DisplayName("F05: processIpnWebhook() on out-of-order failure IPN arriving after order is already PAID must return RspCode 02 without downgrading PAID")
    void processIpnWebhook_OutOfOrderFailureAfterPaid_Returns02WithoutDowngradingOrder() {
        Order paidOrder = Order.builder()
                .id(50L)
                .orderCode("ORD-20261016-050")
                .vnpayTxnRef("ORD-20261016-050")
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-050")).thenReturn(Optional.of(paidOrder));

        Map<String, String> params = signedParams("ORD-20261016-050", "18000000", "24", "02");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("02");
        assertThat(paidOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(paidOrder.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
    }

    @Test
    @DisplayName("processIpnWebhook() on duplicate IPN for already PAID order should return RspCode 02 idempotently")
    void processIpnWebhook_DuplicateIpn_Returns02() {
        Order order = Order.builder()
                .id(50L)
                .orderCode("ORD-20261016-050")
                .vnpayTxnRef("ORD-20261016-050")
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-050")).thenReturn(Optional.of(order));

        Map<String, String> params = signedParams("ORD-20261016-050", "18000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("02");
        assertThat(response.message()).isEqualTo("Order already confirmed");
        verify(stockMutationService, never()).deductOrder(anyLong());
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
    }

    @Test
    @DisplayName("processIpnWebhook() on late payment success for already CANCELLED order (11.1.0.E6) should mark PAID for staff refund without reactivating or deducting stock")
    void processIpnWebhook_LateSuccessAfterCancelled_LogsReconciliationWithoutReactivatingOrder() {
        User customer = User.builder().id(9L).fullName("Le Van C").build();
        Order cancelledOrder = Order.builder()
                .id(60L)
                .user(customer)
                .orderCode("ORD-20261016-060")
                .vnpayTxnRef("ORD-20261016-060")
                .orderStatus(Order.OrderStatus.CANCELLED)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("400000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .stockRestoredAt(BASE_ORDER_CREATED_AT.plusMinutes(16))
                .stockRestoreReason(Order.StockRestoreReason.CANCELLED)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-060")).thenReturn(Optional.of(cancelledOrder));

        Map<String, String> params = signedParams("ORD-20261016-060", "40000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("00");
        assertThat(cancelledOrder.getOrderStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        assertThat(cancelledOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(cancelledOrder.getVnpayPayDate()).isEqualTo("20261016103000");

        // Duplicate late-success IPN must return 02 idempotently once paymentStatus is already PAID
        VNPayIpnResponse duplicateLateResponse = vnPayService.processIpnWebhook(params, "203.0.113.10");
        assertThat(duplicateLateResponse.rspCode()).isEqualTo("02");

        verify(stockMutationService, never()).deductOrder(anyLong());
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
        verify(auditLogService).record(
                eq(9L),
                eq("VNPAY_LATE_SUCCESS_RECONCILIATION"),
                eq("orders"),
                eq(60L),
                any(),
                eq("203.0.113.10")
        );
    }

    // =========================================================================
    // F06: Atomic Voucher Release Failure Propagation
    // =========================================================================

    @Test
    @DisplayName("F06: JdbcOrderVoucherReleaseAdapter should delete order_vouchers and restore vouchers.usage_limit by 1")
    void releaseOrderVoucherIfPresent_DeletesRedemptionAndIncrementsUsageLimit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcOrderVoucherReleaseAdapter adapter = new JdbcOrderVoucherReleaseAdapter(jdbcTemplate);

        when(jdbcTemplate.queryForList(
                eq("SELECT voucher_id FROM order_vouchers WHERE order_id = ?"),
                eq(Long.class),
                eq(40L)
        )).thenReturn(List.of(99L));
        when(jdbcTemplate.update(eq("DELETE FROM order_vouchers WHERE order_id = ?"), eq(40L)))
                .thenReturn(1);
        when(jdbcTemplate.update(eq("UPDATE vouchers SET usage_limit = usage_limit + 1 WHERE id = ?"), eq(99L)))
                .thenReturn(1);

        adapter.releaseOrderVoucherIfPresent(40L);

        verify(jdbcTemplate).update("DELETE FROM order_vouchers WHERE order_id = ?", 40L);
        verify(jdbcTemplate).update("UPDATE vouchers SET usage_limit = usage_limit + 1 WHERE id = ?", 99L);
    }

    @Test
    @DisplayName("F06: JdbcOrderVoucherReleaseAdapter must propagate DataAccessException when voucher quota update fails so the transaction rolls back")
    void releaseOrderVoucherIfPresent_WhenQuotaUpdateThrowsException_PropagatesForRollback() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcOrderVoucherReleaseAdapter adapter = new JdbcOrderVoucherReleaseAdapter(jdbcTemplate);

        when(jdbcTemplate.queryForList(
                eq("SELECT voucher_id FROM order_vouchers WHERE order_id = ?"),
                eq(Long.class),
                eq(40L)
        )).thenReturn(List.of(99L));
        when(jdbcTemplate.update(eq("DELETE FROM order_vouchers WHERE order_id = ?"), eq(40L)))
                .thenReturn(1);
        doThrow(new DataIntegrityViolationException("Simulated voucher quota SQL failure"))
                .when(jdbcTemplate).update(eq("UPDATE vouchers SET usage_limit = usage_limit + 1 WHERE id = ?"), eq(99L));

        assertThatThrownBy(() -> adapter.releaseOrderVoucherIfPresent(40L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Simulated voucher quota SQL failure");
    }

    // =========================================================================
    // F08: Context-Aware Return URL Presentation (SCR-ST-13)
    // =========================================================================

    @Test
    @DisplayName("F08: evaluateBrowserReturn() for confirmed PAID order should return paymentSuccess=true")
    void evaluateBrowserReturn_ConfirmedPaid_ReturnsPaymentSuccessTrue() {
        Order order = Order.builder()
                .id(70L)
                .orderCode("ORD-20261016-070")
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .finalTotalAmount(new BigDecimal("120000.00"))
                .build();
        when(orderRepository.findByOrderCode("ORD-20261016-070")).thenReturn(Optional.of(order));

        Map<String, String> params = signedParams("ORD-20261016-070", "12000000", "00", "00");
        VNPayPaymentResultView view = vnPayService.evaluateBrowserReturn(params);

        assertThat(view.validSignature()).isTrue();
        assertThat(view.paymentSuccess()).isTrue();
        assertThat(view.orderStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(view.paymentStatus()).isEqualTo("PAID");
        assertThat(view.displayMessage()).contains("xác minh thành công");
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("F08: evaluateBrowserReturn() when IPN has not yet updated PENDING_PAYMENT order must return paymentSuccess=false and pending verification message")
    void evaluateBrowserReturn_AwaitingIpnConfirmation_ReturnsPendingVerification() {
        Order order = Order.builder()
                .id(71L)
                .orderCode("ORD-20261016-071")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("120000.00"))
                .build();
        when(orderRepository.findByOrderCode("ORD-20261016-071")).thenReturn(Optional.of(order));

        Map<String, String> params = signedParams("ORD-20261016-071", "12000000", "00", "00");
        VNPayPaymentResultView view = vnPayService.evaluateBrowserReturn(params);

        assertThat(view.validSignature()).isTrue();
        assertThat(view.paymentSuccess()).isFalse();
        assertThat(view.orderStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(view.paymentStatus()).isEqualTo("UNPAID");
        assertThat(view.displayMessage()).contains("đang đối soát và xác minh kết quả thanh toán");
        assertThat(view.displayMessage()).doesNotContain("xác minh thành công");
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("F08: evaluateBrowserReturn() on late payment success for already CANCELLED order must return paymentSuccess=false and reconciliation/refund notice")
    void evaluateBrowserReturn_LateSuccessOnCancelledOrder_ReturnsReconciliationMessage() {
        Order cancelledOrder = Order.builder()
                .id(72L)
                .orderCode("ORD-20261016-072")
                .orderStatus(Order.OrderStatus.CANCELLED)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("120000.00"))
                .build();
        when(orderRepository.findByOrderCode("ORD-20261016-072")).thenReturn(Optional.of(cancelledOrder));

        Map<String, String> params = signedParams("ORD-20261016-072", "12000000", "00", "00");
        VNPayPaymentResultView view = vnPayService.evaluateBrowserReturn(params);

        assertThat(view.validSignature()).isTrue();
        assertThat(view.paymentSuccess()).isFalse();
        assertThat(view.orderStatus()).isEqualTo("CANCELLED");
        assertThat(view.displayMessage()).contains("đối soát");
        assertThat(view.displayMessage()).contains("hoàn tiền");
        assertThat(view.displayMessage()).doesNotContain("Đơn hàng đang chờ nhân viên xác nhận");
    }

    @Test
    @DisplayName("F08: evaluateBrowserReturn() when returning with a retry vnp_TxnRef must resolve the canonical orderCode for UI links")
    void evaluateBrowserReturn_RetryTxnRef_ResolvesCanonicalOrderCode() {
        Order order = Order.builder()
                .id(73L)
                .orderCode("ORD-20261016-073")
                .vnpayTxnRef("ORD-20261016-073-R101200")
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .finalTotalAmount(new BigDecimal("120000.00"))
                .build();
        when(orderRepository.findByVnpayTxnRefOrOrderCode("ORD-20261016-073-R101200"))
                .thenReturn(Optional.of(order));

        Map<String, String> params = signedParams("ORD-20261016-073-R101200", "12000000", "00", "00");
        VNPayPaymentResultView view = vnPayService.evaluateBrowserReturn(params);

        assertThat(view.validSignature()).isTrue();
        assertThat(view.paymentSuccess()).isTrue();
        assertThat(view.orderCode()).isEqualTo("ORD-20261016-073");
        assertThat(view.orderStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(view.paymentStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("processIpnWebhook() with multiple retry attempts should ignore failure from superseded attempt and settle verified success from any attempt")
    void processIpnWebhook_SupersededRetryAttempt_IgnoresOldFailureAndAcceptsSuccess() {
        User customer = User.builder().id(11L).fullName("Multi Tab Customer").build();
        Order order = Order.builder()
                .id(74L)
                .user(customer)
                .orderCode("ORD-20261016-074")
                .vnpayTxnRef("ORD-20261016-074-R100800")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("150000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-074-R100500")).thenReturn(Optional.empty());
        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-074")).thenReturn(Optional.of(order));

        // 1. User cancels older superseded tab (R100500) while newer tab (R100800) is active -> must NOT cancel order
        Map<String, String> oldFailParams = signedParams("ORD-20261016-074-R100500", "15000000", "24", "02");
        VNPayIpnResponse failRes = vnPayService.processIpnWebhook(oldFailParams, "203.0.113.10");
        assertThat(failRes.rspCode()).isEqualTo("00");
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_PAYMENT);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.UNPAID);
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());

        // 2. If user actually paid on an earlier tab (R100500), verified success (00/00) must still settle the order
        Map<String, String> oldSuccessParams = signedParams("ORD-20261016-074-R100500", "15000000", "00", "00");
        VNPayIpnResponse successRes = vnPayService.processIpnWebhook(oldSuccessParams, "203.0.113.10");
        assertThat(successRes.rspCode()).isEqualTo("00");
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(order.getVnpayTxnRef()).isEqualTo("ORD-20261016-074-R100500");
    }

    @Test
    @DisplayName("processIpnWebhook() when two distinct retry attempts both succeed must record surplus payment audit on the second attempt while keeping order processed once")
    void processIpnWebhook_TwoDistinctAttemptsBothSucceed_RecordsSurplusAuditAndProcessesOrderOnce() {
        User customer = User.builder().id(11L).fullName("Double Paid Customer").build();
        Order order = Order.builder()
                .id(75L)
                .user(customer)
                .orderCode("ORD-20261016-075")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("150000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();
        order.setCreatedAt(BASE_ORDER_CREATED_AT);

        when(orderRepository.findByIdWithLock(75L)).thenReturn(Optional.of(order));
        authenticateAsCustomer(customer);

        // Customer initiates Attempt 1 (ORD-20261016-075) and Retry Attempt 2 (ORD-20261016-075-R100500)
        vnPayService.setClock(fixedVietnamClock(BASE_ORDER_CREATED_AT.plusMinutes(2)));
        vnPayService.createPaymentUrl(customer.getId(), 75L, "127.0.0.1");
        vnPayService.setClock(fixedVietnamClock(BASE_ORDER_CREATED_AT.plusMinutes(5)));
        vnPayService.createPaymentUrl(customer.getId(), 75L, "127.0.0.1");

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-075")).thenReturn(Optional.of(order));
        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-075-R100500")).thenReturn(Optional.of(order));

        // 1. First successful IPN (Attempt 1: ORD-20261016-075) settles the order once
        Map<String, String> firstSuccess = signedParams("ORD-20261016-075", "15000000", "00", "00");
        VNPayIpnResponse res1 = vnPayService.processIpnWebhook(firstSuccess, "203.0.113.10");
        assertThat(res1.rspCode()).isEqualTo("00");
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(order.getVnpayTxnRef()).isEqualTo("ORD-20261016-075");

        // 2. Second successful IPN from a distinct retry attempt (ORD-20261016-075-R100500) must NOT be silently ignored as 02;
        //    it must record a second audit log for surplus payment reconciliation/refund while keeping order processed once
        Map<String, String> secondSuccess = signedParams("ORD-20261016-075-R100500", "15000000", "00", "00");
        VNPayIpnResponse res2 = vnPayService.processIpnWebhook(secondSuccess, "203.0.113.10");
        assertThat(res2.rspCode()).isEqualTo("00");
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(order.getVnpayTxnRef()).isEqualTo("ORD-20261016-075");

        // 3. True duplicate re-delivery of the second attempt's IPN must return 02 without creating a third audit
        VNPayIpnResponse duplicateOfSecond = vnPayService.processIpnWebhook(secondSuccess, "203.0.113.10");
        assertThat(duplicateOfSecond.rspCode()).isEqualTo("02");

        verify(stockMutationService, never()).deductOrder(anyLong());
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
        verify(auditLogService).record(
                eq(11L),
                eq("VNPAY_PAYMENT_SUCCESS"),
                eq("orders"),
                eq(75L),
                any(),
                eq("203.0.113.10")
        );
        verify(auditLogService).record(
                eq(11L),
                eq("VNPAY_DUPLICATE_PAYMENT_RECONCILIATION"),
                eq("orders"),
                eq(75L),
                any(),
                eq("203.0.113.10")
        );
    }

    @Test
    @DisplayName("createPaymentUrl() called 4+ times within the same second must generate strictly unique non-reused vnp_TxnRef values")
    void createPaymentUrl_FourRetriesWithinSameSecond_NeverReusesTxnRef() throws Exception {
        Order order = Order.builder()
                .id(76L)
                .user(ownerCustomer)
                .orderCode("CODE")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();
        order.setCreatedAt(BASE_ORDER_CREATED_AT);

        when(orderRepository.findByIdWithLock(76L)).thenReturn(Optional.of(order));

        // Freeze clock at 10:02:00 for all 5 calls
        vnPayService.setClock(fixedVietnamClock(BASE_ORDER_CREATED_AT.plusMinutes(2)));

        vnPayService.createPaymentUrl(ownerCustomer.getId(), 76L, "127.0.0.1");
        String ref1 = order.getVnpayTxnRef();

        vnPayService.createPaymentUrl(ownerCustomer.getId(), 76L, "127.0.0.1");
        String ref2 = order.getVnpayTxnRef();

        vnPayService.createPaymentUrl(ownerCustomer.getId(), 76L, "127.0.0.1");
        String ref3 = order.getVnpayTxnRef();

        // Simulate reloading Order from persisted DB columns before the 4th call
        java.lang.reflect.Field rawPayDateField = Order.class.getDeclaredField("vnpayPayDate");
        rawPayDateField.setAccessible(true);
        java.lang.reflect.Field rawBankCodeField = Order.class.getDeclaredField("vnpayBankCode");
        rawBankCodeField.setAccessible(true);

        Order reloadedOrder = Order.builder()
                .id(76L)
                .user(ownerCustomer)
                .orderCode("CODE")
                .vnpayTxnRef(order.getVnpayTxnRef())
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();
        reloadedOrder.setCreatedAt(BASE_ORDER_CREATED_AT);
        rawPayDateField.set(reloadedOrder, rawPayDateField.get(order));
        rawBankCodeField.set(reloadedOrder, rawBankCodeField.get(order));

        when(orderRepository.findByIdWithLock(76L)).thenReturn(Optional.of(reloadedOrder));

        vnPayService.createPaymentUrl(ownerCustomer.getId(), 76L, "127.0.0.1");
        String ref4 = reloadedOrder.getVnpayTxnRef();

        vnPayService.createPaymentUrl(ownerCustomer.getId(), 76L, "127.0.0.1");
        String ref5 = reloadedOrder.getVnpayTxnRef();

        assertThat(ref1).isEqualTo("CODE");
        assertThat(ref2).isEqualTo("CODE-R100200");
        assertThat(ref3).isEqualTo("CODE-R100200-2");
        assertThat(ref4).isEqualTo("CODE-R100200-3");
        assertThat(ref5).isEqualTo("CODE-R100200-4");
        assertThat(List.of(ref1, ref2, ref3, ref4, ref5)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("VNPayUtil.resolveClientIp() should normalize IPv6 loopback and public IPv6 headers to IPv4 / 127.0.0.1")
    void resolveClientIp_NormalizesIpv6Loopback() {
        HttpServletRequest reqIpv6Full = mock(HttpServletRequest.class);
        when(reqIpv6Full.getRemoteAddr()).thenReturn("0:0:0:0:0:0:0:1");
        assertThat(VNPayUtil.resolveClientIp(reqIpv6Full)).isEqualTo("127.0.0.1");

        HttpServletRequest reqIpv6Short = mock(HttpServletRequest.class);
        when(reqIpv6Short.getRemoteAddr()).thenReturn("::1");
        assertThat(VNPayUtil.resolveClientIp(reqIpv6Short)).isEqualTo("127.0.0.1");

        HttpServletRequest reqPublicIpv6 = mock(HttpServletRequest.class);
        when(reqPublicIpv6.getHeader("X-Forwarded-For")).thenReturn("2402:800:61c5:1234::1");
        assertThat(VNPayUtil.resolveClientIp(reqPublicIpv6)).isEqualTo("127.0.0.1");

        HttpServletRequest reqDualStackChain = mock(HttpServletRequest.class);
        when(reqDualStackChain.getHeader("X-Forwarded-For")).thenReturn("2402:800:61c5:1234::1, 203.113.148.25");
        assertThat(VNPayUtil.resolveClientIp(reqDualStackChain)).isEqualTo("203.113.148.25");
    }

    @Test
    @DisplayName("P2 Regression: Duplicate surplus payment IPN across two separately loaded Order entities returns 02 and audits only once")
    void processIpnWebhook_SurplusPaymentDuplicateAcrossSeparatelyLoadedEntities_Returns02AndAuditsOnce() {
        User customer = User.builder().id(77L).fullName("Surplus Customer").build();

        // Entity 1: initial PAID order
        Order entity1 = Order.builder()
                .id(77L)
                .user(customer)
                .orderCode("ORD-DUAL-ENTITY")
                .vnpayTxnRef("ORD-DUAL-ENTITY")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .finalTotalAmount(new BigDecimal("200000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();
        entity1.recordSettledVnpayAttempt("ORD-DUAL-ENTITY", "14500001");

        // Incoming surplus IPN for retry attempt ORD-DUAL-ENTITY-R100200
        Map<String, String> surplusParams = signedParams("ORD-DUAL-ENTITY-R100200", "20000000", "00", "00");
        surplusParams.put("vnp_TransactionNo", "14500002");
        surplusParams.put("vnp_BankCode", "NCB");
        String hashData = VNPayUtil.buildHashData(surplusParams);
        surplusParams.put("vnp_SecureHash", VNPayUtil.hmacSHA512(TEST_SECRET, hashData));

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-DUAL-ENTITY-R100200"))
                .thenReturn(Optional.empty());
        when(orderRepository.findByVnpayTxnRefWithLock("ORD-DUAL-ENTITY"))
                .thenReturn(Optional.of(entity1));

        // First delivery: should be detected as surplus payment, return 00, audit VNPAY_DUPLICATE_PAYMENT_RECONCILIATION
        VNPayIpnResponse res1 = vnPayService.processIpnWebhook(surplusParams, "203.0.113.10");
        assertThat(res1.rspCode()).isEqualTo("00");

        verify(auditLogService, org.mockito.Mockito.times(1)).record(
                eq(77L),
                eq("VNPAY_DUPLICATE_PAYMENT_RECONCILIATION"),
                eq("orders"),
                eq(77L),
                any(),
                eq("203.0.113.10")
        );

        // Second delivery: simulate fresh Order entity loaded separately from database with persisted columns
        Order entity2 = Order.builder()
                .id(77L)
                .user(customer)
                .orderCode("ORD-DUAL-ENTITY")
                .vnpayTxnRef("ORD-DUAL-ENTITY")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .finalTotalAmount(new BigDecimal("200000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .vnpayBankCode(entity1.getPersistedVnpayBankCodeColumn())
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-DUAL-ENTITY"))
                .thenReturn(Optional.of(entity2));

        // Second delivery of the EXACT same surplus payment callback
        VNPayIpnResponse res2 = vnPayService.processIpnWebhook(surplusParams, "203.0.113.10");

        // Must return "02" (Order already confirmed / duplicate) and NOT audit surplus again!
        assertThat(res2.rspCode()).isEqualTo("02");
        assertThat(res2.message()).containsIgnoringCase("Order already confirmed");

        // Verify total audit log count remains 1 (no duplicate audit created!)
        verify(auditLogService, org.mockito.Mockito.times(1)).record(
                eq(77L),
                eq("VNPAY_DUPLICATE_PAYMENT_RECONCILIATION"),
                eq("orders"),
                eq(77L),
                any(),
                eq("203.0.113.10")
        );
    }

    @Test
    @DisplayName("P1: processIpnWebhook() does not update deduplication cache when transaction rolls back, enabling retry to audit")
    void processIpnWebhook_TransactionRollbackDoesNotPolluteDeduplicationCache_SubsequentRetryAuditsSuccessfully() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            User customer = User.builder().id(88L).fullName("Pham Van Rollback").build();
            Order entity1 = Order.builder()
                    .id(88L)
                    .user(customer)
                    .orderCode("ORD-ROLLBACK-TEST")
                    .vnpayTxnRef("ORD-ROLLBACK-TEST")
                    .paymentMethod(Order.PaymentMethod.VNPAY)
                    .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                    .paymentStatus(Order.PaymentStatus.PAID)
                    .finalTotalAmount(new BigDecimal("300000.00"))
                    .stockDeductedAt(BASE_ORDER_CREATED_AT)
                    .build();

            when(orderRepository.findByVnpayTxnRefWithLock("ORD-ROLLBACK-TEST-R100200"))
                    .thenReturn(Optional.of(entity1));

            Map<String, String> surplusParams = signedParams("ORD-ROLLBACK-TEST-R100200", "30000000", "00", "00");

            // First execution runs
            VNPayIpnResponse res1 = vnPayService.processIpnWebhook(surplusParams, "203.0.113.10");
            assertThat(res1.rspCode()).isEqualTo("00");

            // Simulate transaction failure / rollback: afterCommit is NOT called, only afterCompletion(ROLLED_BACK)
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // Second delivery (retry of the exact same callback after the rollback)
        // Entity reloaded from database has rolled-back state (no settled markers saved)
        User customer = User.builder().id(88L).fullName("Pham Van Rollback").build();
        Order entity2 = Order.builder()
                .id(88L)
                .user(customer)
                .orderCode("ORD-ROLLBACK-TEST")
                .vnpayTxnRef("ORD-ROLLBACK-TEST")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .finalTotalAmount(new BigDecimal("300000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-ROLLBACK-TEST-R100200"))
                .thenReturn(Optional.of(entity2));

        Map<String, String> surplusParams = signedParams("ORD-ROLLBACK-TEST-R100200", "30000000", "00", "00");
        VNPayIpnResponse res2 = vnPayService.processIpnWebhook(surplusParams, "203.0.113.10");

        // Must succeed and record audit, NOT return "02" (cache was not contaminated!)
        assertThat(res2.rspCode()).isEqualTo("00");
        verify(auditLogService, org.mockito.Mockito.times(2)).record(
                eq(88L),
                eq("VNPAY_DUPLICATE_PAYMENT_RECONCILIATION"),
                eq("orders"),
                eq(88L),
                any(),
                eq("203.0.113.10")
        );
    }

    @Test
    @DisplayName("P2: Order losslessly preserves deduplication for 5+ surplus payments with dummy data within 50 chars")
    void order_SurplusPaymentsBeyondFourAttempts_PreservesDeduplicationWithoutExceedingColumnLength() {
        Order order = Order.builder()
                .id(99L)
                .orderCode("ORD-SURPLUS-CHAIN")
                .vnpayTxnRef("ORD-SURPLUS-CHAIN")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .vnpayBankCode("NCB")
                .build();

        // Record 7 consecutive distinct surplus payments using arbitrary dummy data
        for (int i = 1; i <= 7; i++) {
            String txnRef = "DUMMY-REF-" + i;
            String txNo = "DUMMY-TX-" + i;
            assertThat(order.isDistinctSuccessfulVnpayAttempt(txnRef, txNo)).isTrue();
            order.recordSettledVnpayAttempt(txnRef, txNo);

            // Column length must never exceed 50 characters at any point!
            String col = order.getPersistedVnpayBankCodeColumn();
            assertThat(col).isNotNull();
            assertThat(col.length()).isLessThanOrEqualTo(50);
        }

        // Simulate persisting and reloading fresh entity from MySQL columns
        String persistedCol = order.getPersistedVnpayBankCodeColumn();
        Order reloaded = Order.builder()
                .id(99L)
                .orderCode("ORD-SURPLUS-CHAIN")
                .vnpayTxnRef("ORD-SURPLUS-CHAIN")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .vnpayBankCode(persistedCol)
                .build();

        // All 7 previously settled attempts must be recognized as already settled (isDistinct == false)
        for (int i = 1; i <= 7; i++) {
            String txnRef = "DUMMY-REF-" + i;
            String txNo = "DUMMY-TX-" + i;
            assertThat(reloaded.isDistinctSuccessfulVnpayAttempt(txnRef, txNo))
                    .as("Attempt %d must be recognized as already settled after entity reload", i)
                    .isFalse();
        }

        // A brand new attempt must still be recognized as distinct (isDistinct == true)
        assertThat(reloaded.isDistinctSuccessfulVnpayAttempt("DUMMY-REF-NEW", "DUMMY-TX-NEW")).isTrue();
    }

    @Test
    @DisplayName("P1: Bloom filter collision must not cause a genuine new payment attempt to be rejected as duplicate")
    void order_BloomFilterCollision_DoesNotFalselyRejectNewPayment() {
        Order order = Order.builder()
                .id(99L)
                .orderCode("CODE")
                .vnpayTxnRef("CODE")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .vnpayBankCode("NCB")
                .build();

        for (int i = 1; i <= 7; i++) {
            order.recordSettledVnpayAttempt("CODE-R100" + i + "00", "1450000" + i);
        }

        boolean distinctOnClass = order.isDistinctSuccessfulVnpayAttempt("CODE-R100711", "14500008");
        assertThat(distinctOnClass).isTrue();
    }

    @Test
    @DisplayName("P1: processIpnWebhook() with Bloom filter candidate collision verifies against persistent audit ledger and audits surplus payment")
    void processIpnWebhook_BloomFilterCandidateCollisionVerifiedAgainstPersistentLedger_AuditsSurplusPayment() {
        User customer = User.builder().id(99L).fullName("Collision Customer").build();
        Order order = Order.builder()
                .id(99L)
                .user(customer)
                .orderCode("CODE")
                .vnpayTxnRef("CODE")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .vnpayBankCode("NCB")
                .build();

        // Record 7 settled payments to fill bank code with Bloom filter
        for (int i = 1; i <= 7; i++) {
            order.recordSettledVnpayAttempt("CODE-R100" + i + "00", "1450000" + i);
        }

        // Simulate reloading fresh entity from MySQL columns (in-memory sets cleared, only persisted bank code present)
        Order reloaded = Order.builder()
                .id(99L)
                .user(customer)
                .orderCode("CODE")
                .vnpayTxnRef("CODE")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(BASE_ORDER_CREATED_AT)
                .vnpayBankCode(order.getPersistedVnpayBankCodeColumn())
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("CODE-R100711")).thenReturn(Optional.of(reloaded));

        Map<String, String> params = signedParams("CODE-R100711", "25000000", "00", "00");
        params.put("vnp_TransactionNo", "14500008");
        String hashData = VNPayUtil.buildHashData(params);
        params.put("vnp_SecureHash", VNPayUtil.hmacSHA512(TEST_SECRET, hashData));

        // Attempt not yet in persistent audit ledger
        when(auditLogService.hasSettledVnpayPayment(99L, "CODE-R100711", "14500008")).thenReturn(false);

        // First delivery: must return 00 and create financial reconciliation audit log (not discarded with 02!)
        VNPayIpnResponse res1 = vnPayService.processIpnWebhook(params, "203.0.113.10");
        assertThat(res1.rspCode()).isEqualTo("00");

        verify(auditLogService).record(
                eq(99L),
                eq("VNPAY_DUPLICATE_PAYMENT_RECONCILIATION"),
                eq("orders"),
                eq(99L),
                any(),
                eq("203.0.113.10")
        );

        // Second delivery (network retry of the same IPN callback): now verified in persistent audit ledger
        lenient().when(auditLogService.hasSettledVnpayPayment(99L, "CODE-R100711", "14500008")).thenReturn(true);
        VNPayIpnResponse res2 = vnPayService.processIpnWebhook(params, "203.0.113.10");

        // Must return 02 idempotently without repeating audit log
        assertThat(res2.rspCode()).isEqualTo("02");
        verify(auditLogService, times(1)).record(
                eq(99L),
                eq("VNPAY_DUPLICATE_PAYMENT_RECONCILIATION"),
                eq("orders"),
                eq(99L),
                any(),
                eq("203.0.113.10")
        );
    }

    @Test
    @DisplayName("PAY-02: createPaymentUrl() rejects null orderId and non-VNPay orders before locking")
    void createPaymentUrl_NullOrNonVnpayOrder_ThrowsInvalidInput() {
        assertThatThrownBy(() -> vnPayService.createPaymentUrl(1L, null, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);

        Order codOrder = Order.builder()
                .id(120L)
                .orderCode("ORD-COD-120")
                .user(ownerCustomer)
                .paymentMethod(Order.PaymentMethod.COD)
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("100000.00"))
                .stockDeductedAt(LocalDateTime.now())
                .build();
        when(orderRepository.findByIdWithLock(120L)).thenReturn(Optional.of(codOrder));

        assertThatThrownBy(() -> vnPayService.createPaymentUrl(1L, 120L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("PAY-03: evaluateBrowserReturn() handles null or empty query parameters safely without DB mutation")
    void evaluateBrowserReturn_NullOrEmptyParams_ReturnsInvalidSignatureSafely() {
        VNPayPaymentResultView nullView = vnPayService.evaluateBrowserReturn(null);
        VNPayPaymentResultView emptyView = vnPayService.evaluateBrowserReturn(Map.of());

        assertThat(nullView.validSignature()).isFalse();
        assertThat(nullView.paymentSuccess()).isFalse();
        assertThat(nullView.orderStatus()).isEqualTo("UNKNOWN");
        assertThat(emptyView.validSignature()).isFalse();
        assertThat(emptyView.paymentSuccess()).isFalse();
        assertThat(emptyView.paymentStatus()).isEqualTo("UNKNOWN");
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("PAY-08: Concurrent identical IPN callbacks serialize under lock and confirm payment exactly once")
    void processIpnWebhook_ConcurrentCallbacks_SerializesUnderLockAndConfirmsOnce() throws Exception {
        Order lockedOrder = Order.builder()
                .id(130L)
                .orderCode("ORD-CONCURRENT-130")
                .user(ownerCustomer)
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("300000.00"))
                .stockDeductedAt(LocalDateTime.now())
                .build();

        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch startRace = new CountDownLatch(1);
        CountDownLatch firstCommitted = new CountDownLatch(1);
        AtomicInteger lockOrderIndex = new AtomicInteger(0);

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-CONCURRENT-130")).thenAnswer(invocation -> {
            if (lockOrderIndex.getAndIncrement() > 0) {
                firstCommitted.await(5, TimeUnit.SECONDS);
            }
            return Optional.of(lockedOrder);
        });
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, String> params = signedParams("ORD-CONCURRENT-130", "30000000", "00", "00");
        Callable<VNPayIpnResponse> callbackTask = () -> {
            bothEntered.countDown();
            startRace.await(5, TimeUnit.SECONDS);
            try {
                return vnPayService.processIpnWebhook(params, "203.0.113.20");
            } finally {
                firstCommitted.countDown();
            }
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<VNPayIpnResponse> future1 = pool.submit(callbackTask);
            Future<VNPayIpnResponse> future2 = pool.submit(callbackTask);
            assertThat(bothEntered.await(5, TimeUnit.SECONDS)).isTrue();
            startRace.countDown();

            VNPayIpnResponse res1 = future1.get(5, TimeUnit.SECONDS);
            VNPayIpnResponse res2 = future2.get(5, TimeUnit.SECONDS);
            assertThat(List.of(res1.rspCode(), res2.rspCode())).containsExactlyInAnyOrder("00", "02");
        }

        assertThat(lockedOrder.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        assertThat(lockedOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        verify(auditLogService, times(1)).record(
                eq(1L),
                eq("VNPAY_PAYMENT_SUCCESS"),
                eq("orders"),
                eq(130L),
                any(),
                eq("203.0.113.20")
        );
        verifyNoInteractions(stockMutationService);
    }

    private void authenticateAsCustomer(User user) {
        lenient().when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        user.getEmail(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                )
        );
    }

    private Clock fixedVietnamClock(LocalDateTime dateTime) {
        return Clock.fixed(dateTime.atZone(VNPayServiceImpl.VIETNAM_ZONE).toInstant(), VNPayServiceImpl.VIETNAM_ZONE);
    }

    private Map<String, String> signedParams(String txnRef, String amountMinor, String responseCode, String txStatus) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Amount", amountMinor);
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_OrderInfo", "Thanh toan don hang " + txnRef);
        params.put("vnp_PayDate", "20261016103000");
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TmnCode", "AURELIA01");
        params.put("vnp_TransactionNo", "14598201");
        if (txStatus != null) {
            params.put("vnp_TransactionStatus", txStatus);
        }
        params.put("vnp_TxnRef", txnRef);
        String hashData = VNPayUtil.buildHashData(params);
        params.put("vnp_SecureHash", VNPayUtil.hmacSHA512(TEST_SECRET, hashData));
        return params;
    }
}
