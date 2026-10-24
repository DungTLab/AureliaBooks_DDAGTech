package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.VNPayIpnResponse;
import com.ddagtech.aureliabooks.dto.response.VNPayPaymentResultView;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.service.impl.NoOpOrderVoucherReleaseAdapter;
import com.ddagtech.aureliabooks.service.impl.VNPayServiceImpl;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for {@link VNPayServiceImpl} (UC11.1 - Pay via VNPay).
 * Verifies V2.4 invariants:
 * - Available inventory is already deducted at checkout (UC11) before VNPay initiation.
 * - IPN payment success transitions order to PENDING_CONFIRMATION and PAID without deducting stock again (BR-04-02).
 * - IPN payment failure or cancellation cancels order, restores deducted stock once, and releases voucher.
 * - Late payment success arriving after order cancellation (11.1.0.E6) logs reconciliation without reactivating or re-deducting stock.
 * - Browser Return URL (SCR-ST-13) is strictly presentation-only.
 */
@ExtendWith(MockitoExtension.class)
class VNPayServiceTest {

    private static final String TEST_SECRET = "SECRETKEY1234567890ABCDEF12345678";

    @Spy
    private VNPayConfig vnPayConfig = new VNPayConfig();

    @Mock
    private OrderRepository orderRepository;

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

    @BeforeEach
    void setUp() {
        vnPayConfig.setTmnCode("AURELIA01");
        vnPayConfig.setHashSecret(TEST_SECRET);
        vnPayConfig.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        vnPayConfig.setReturnUrl("http://localhost:8080/payment/vnpay-return");
        vnPayConfig.setVersion("2.1.0");
        vnPayConfig.setCommand("pay");
        vnPayConfig.setOrderType("other");
        vnPayConfig.setExpireMinutes(15);
    }

    @Test
    @DisplayName("createPaymentUrl() should generate signed VNPay URL when order is PENDING_PAYMENT and stock is already deducted")
    void createPaymentUrl_ValidPendingOrder_GeneratesSignedUrl() {
        Order order = Order.builder()
                .id(10L)
                .orderCode("ORD-20261016-001")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(LocalDateTime.now().minusMinutes(1))
                .build();

        when(orderRepository.findByIdWithLock(10L)).thenReturn(Optional.of(order));

        String url = vnPayService.createPaymentUrl(10L, "127.0.0.1");

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(url).contains("vnp_Amount=25000000");
        assertThat(url).contains("vnp_TxnRef=ORD-20261016-001");
        assertThat(url).contains("vnp_SecureHash=");
        assertThat(order.getVnpayTxnRef()).isEqualTo("ORD-20261016-001");
    }

    @Test
    @DisplayName("createPaymentUrl() should reject order whose stock was not deducted at order creation (BR-04-02)")
    void createPaymentUrl_StockNotDeducted_ThrowsException() {
        Order order = Order.builder()
                .id(11L)
                .orderCode("ORD-20261016-002")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("150000.00"))
                .stockDeductedAt(null)
                .build();

        when(orderRepository.findByIdWithLock(11L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> vnPayService.createPaymentUrl(11L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ORDER_STOCK_NOT_DEDUCTED);
    }

    @Test
    @DisplayName("processIpnWebhook() should reject forged checksum with RspCode 97 without touching database")
    void processIpnWebhook_InvalidSignature_Returns97() {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_TxnRef", "ORD-20261016-001");
        params.put("vnp_Amount", "25000000");
        params.put("vnp_ResponseCode", "00");
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
                .stockDeductedAt(LocalDateTime.now().minusMinutes(2))
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-020")).thenReturn(Optional.of(order));

        Map<String, String> params = signedParams("ORD-20261016-020", "10000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("04");
        assertThat(response.message()).isEqualTo("Invalid Amount");
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("processIpnWebhook() on verified success (00) should transition to PAID & PENDING_CONFIRMATION WITHOUT deducting stock again (BR-04-02)")
    void processIpnWebhook_VerifiedSuccess_UpdatesOrderAndPaymentWithoutDuplicateStockDeduction() {
        User customer = User.builder().id(5L).fullName("Nguyen Van A").build();
        LocalDateTime initialDeductionTime = LocalDateTime.now().minusMinutes(3);
        Order order = Order.builder()
                .id(30L)
                .user(customer)
                .orderCode("ORD-20261016-030")
                .vnpayTxnRef("ORD-20261016-030")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("320000.00"))
                .stockDeductedAt(initialDeductionTime)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-030")).thenReturn(Optional.of(order));

        Map<String, String> params = signedParams("ORD-20261016-030", "32000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("00");
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        assertThat(order.getVnpayBankCode()).isEqualTo("NCB");
        assertThat(order.getVnpayPayDate()).isEqualTo("20261016103000");
        assertThat(order.getStockDeductedAt()).isEqualTo(initialDeductionTime);

        // Critical V2.4 Invariant: stock is NEVER deducted again upon IPN callback
        verify(stockMutationService, never()).deductOrder(anyLong());
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
        verify(orderRepository).save(order);
        verify(auditLogService).record(eq(5L), eq("VNPAY_PAYMENT_SUCCESS"), eq("orders"), eq(30L), any(), eq("203.0.113.10"));
    }

    @Test
    @DisplayName("processIpnWebhook() on verified failure/cancellation (24) should cancel order, restore stock once, and release voucher (BR-04-02)")
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
                .stockDeductedAt(LocalDateTime.now().minusMinutes(4))
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
    @DisplayName("processIpnWebhook() on duplicate IPN for already PAID order should return RspCode 02 idempotently")
    void processIpnWebhook_DuplicateIpn_Returns02() {
        Order order = Order.builder()
                .id(50L)
                .orderCode("ORD-20261016-050")
                .vnpayTxnRef("ORD-20261016-050")
                .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                .paymentStatus(Order.PaymentStatus.PAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .stockDeductedAt(LocalDateTime.now().minusMinutes(5))
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
    @DisplayName("processIpnWebhook() on late payment success for already CANCELLED order (11.1.0.E6) should log reconciliation without reactivating or deducting stock")
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
                .stockDeductedAt(LocalDateTime.now().minusMinutes(20))
                .stockRestoredAt(LocalDateTime.now().minusMinutes(4))
                .stockRestoreReason(Order.StockRestoreReason.CANCELLED)
                .build();

        when(orderRepository.findByVnpayTxnRefWithLock("ORD-20261016-060")).thenReturn(Optional.of(cancelledOrder));

        Map<String, String> params = signedParams("ORD-20261016-060", "40000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, "203.0.113.10");

        assertThat(response.rspCode()).isEqualTo("00");
        // Order must remain CANCELLED and must NOT auto-transition to REFUNDED before manual refund confirmation (BR-04-06)
        assertThat(cancelledOrder.getOrderStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        assertThat(cancelledOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.UNPAID);
        assertThat(cancelledOrder.getVnpayPayDate()).isEqualTo("20261016103000");

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

    @Test
    @DisplayName("evaluateBrowserReturn() should return read-only view for SCR-ST-13 without mutating database state")
    void evaluateBrowserReturn_ReadOnlyPresentation() {
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
        assertThat(view.orderCode()).isEqualTo("ORD-20261016-070");
        assertThat(view.amountVnd()).isEqualByComparingTo("120000.00");
        assertThat(view.orderStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(view.paymentStatus()).isEqualTo("PAID");

        verify(orderRepository, never()).save(any());
        verify(stockMutationService, never()).deductOrder(anyLong());
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
    }

    @Test
    @DisplayName("VNPayUtil.resolveClientIp() should normalize IPv6 loopback addresses to 127.0.0.1")
    void resolveClientIp_NormalizesIpv6Loopback() {
        HttpServletRequest reqIpv6Full = mock(HttpServletRequest.class);
        when(reqIpv6Full.getRemoteAddr()).thenReturn("0:0:0:0:0:0:0:1");
        assertThat(VNPayUtil.resolveClientIp(reqIpv6Full)).isEqualTo("127.0.0.1");

        HttpServletRequest reqIpv6Short = mock(HttpServletRequest.class);
        when(reqIpv6Short.getRemoteAddr()).thenReturn("::1");
        assertThat(VNPayUtil.resolveClientIp(reqIpv6Short)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("NoOpOrderVoucherReleaseAdapter should delete order_vouchers and restore vouchers.usage_limit by 1")
    void releaseOrderVoucherIfPresent_DeletesRedemptionAndIncrementsUsageLimit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        NoOpOrderVoucherReleaseAdapter adapter = new NoOpOrderVoucherReleaseAdapter(jdbcTemplate);

        when(jdbcTemplate.queryForList(
                eq("SELECT voucher_id FROM order_vouchers WHERE order_id = ?"),
                eq(Long.class),
                eq(40L)
        )).thenReturn(List.of(99L));
        when(jdbcTemplate.update(eq("DELETE FROM order_vouchers WHERE order_id = ?"), eq(40L)))
                .thenReturn(1);

        adapter.releaseOrderVoucherIfPresent(40L);

        verify(jdbcTemplate).update("DELETE FROM order_vouchers WHERE order_id = ?", 40L);
        verify(jdbcTemplate).update("UPDATE vouchers SET usage_limit = usage_limit + 1 WHERE id = ?", 99L);
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
        params.put("vnp_TransactionStatus", txStatus);
        params.put("vnp_TxnRef", txnRef);
        String hashData = VNPayUtil.buildHashData(params);
        params.put("vnp_SecureHash", VNPayUtil.hmacSHA512(TEST_SECRET, hashData));
        return params;
    }
}
