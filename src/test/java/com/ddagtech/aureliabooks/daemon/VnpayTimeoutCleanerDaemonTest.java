package com.ddagtech.aureliabooks.daemon;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.service.OrderVoucherReleasePort;
import com.ddagtech.aureliabooks.service.StockMutationService;
import com.ddagtech.aureliabooks.service.VNPayGatewayReconciliationClient;
import com.ddagtech.aureliabooks.service.VNPayGatewayReconciliationClient.ReconciliationOutcome;
import com.ddagtech.aureliabooks.service.VNPayGatewayReconciliationClient.ReconciliationResult;
import com.ddagtech.aureliabooks.service.impl.DefaultVNPayGatewayReconciliationClient;
import com.ddagtech.aureliabooks.service.impl.NoOpOrderVoucherReleaseAdapter;
import com.ddagtech.aureliabooks.service.impl.VNPayServiceImpl;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit and HTTP stub integration test suite for CRON-01:
 * {@link VnpayTimeoutCleanerDaemon} and {@link DefaultVNPayGatewayReconciliationClient}
 * (V2.4 §5.2 / BR-04-04 / F01 / F02 / F06 / F09 / F10).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CRON-01: VNPay Timeout Cleaner & Gateway QueryDR Reconciliation Tests")
class VnpayTimeoutCleanerDaemonTest {

    private static final String TEST_SECRET = "CRON01_QUERYDR_HMAC_SECRET_0123456789ABCDEF";

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private VNPayGatewayReconciliationClient reconciliationClient;

    @Mock
    private StockMutationService stockMutationService;

    @Mock
    private OrderVoucherReleasePort orderVoucherReleasePort;

    @Mock
    private AuditLogService auditLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer stubServer;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        stopStubServer();
    }

    private void stopStubServer() {
        if (stubServer != null) {
            stubServer.stop(0);
            stubServer = null;
        }
    }

    // =========================================================================
    // Daemon Orchestration & F02 Stale L1 Persistence Context Refresh Tests
    // =========================================================================

    @Test
    @DisplayName("CRON-01: cleanExpiredPendingPaymentOrders() should cancel and restock once when reconciliation verifies unpaid (BR-04-04)")
    void cron01_VerifiedUnpaid_CancelsOrderRestoresStockOnceAndReleasesVoucher() {
        VNPayConfig config = new VNPayConfig();
        config.setExpireMinutes(15);

        VnpayTimeoutCleanerDaemon daemon = new VnpayTimeoutCleanerDaemon(
                orderRepository,
                config,
                reconciliationClient,
                stockMutationService,
                orderVoucherReleasePort,
                auditLogService,
                objectMapper
        );

        User customer = User.builder().id(12L).build();
        Order expiredOrder = Order.builder()
                .id(101L)
                .user(customer)
                .orderCode("ORD-EXP-101")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .stockDeductedAt(LocalDateTime.now().minusMinutes(20))
                .build();

        when(orderRepository.findExpiredPendingPaymentOrders(any())).thenReturn(List.of(expiredOrder));
        when(orderRepository.findByIdWithLock(101L)).thenReturn(Optional.of(expiredOrder));
        when(reconciliationClient.reconcileExpiredOrder(expiredOrder))
                .thenReturn(ReconciliationResult.unpaidOrFailed("Verified terminal failure at gateway"));

        int cleaned = daemon.cleanExpiredPendingPaymentOrders();

        assertThat(cleaned).isEqualTo(1);
        assertThat(expiredOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.UNPAID);
        verify(stockMutationService).restoreCancelledOrder(101L, 12L);
        verify(orderVoucherReleasePort).releaseOrderVoucherIfPresent(101L);
        verify(auditLogService).record(
                eq(12L),
                eq("VNPAY_TIMEOUT_CANCELLED_RESTOCK"),
                eq("orders"),
                eq(101L),
                any(),
                eq("SYSTEM_CRON_01")
        );
    }

    @Test
    @DisplayName("F06 (CRON-01 Fault Injection): SQL failure during voucher release in CRON-01 must propagate to roll back order cancellation and stock restoration")
    void cron01_VoucherReleaseSqlFailure_PropagatesExceptionForAtomicRollback() {
        VNPayConfig config = new VNPayConfig();
        config.setExpireMinutes(15);

        JdbcTemplate failingJdbcTemplate = mock(JdbcTemplate.class);
        NoOpOrderVoucherReleaseAdapter realVoucherAdapter = new NoOpOrderVoucherReleaseAdapter(failingJdbcTemplate);

        VnpayTimeoutCleanerDaemon daemon = new VnpayTimeoutCleanerDaemon(
                orderRepository,
                config,
                reconciliationClient,
                stockMutationService,
                realVoucherAdapter,
                auditLogService,
                objectMapper
        );

        User customer = User.builder().id(12L).build();
        Order expiredOrder = Order.builder()
                .id(109L)
                .user(customer)
                .orderCode("ORD-EXP-VOUCHER-FAIL")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .stockDeductedAt(LocalDateTime.now().minusMinutes(20))
                .build();

        when(orderRepository.findExpiredPendingPaymentOrders(any())).thenReturn(List.of(expiredOrder));
        when(orderRepository.findByIdWithLock(109L)).thenReturn(Optional.of(expiredOrder));
        when(reconciliationClient.reconcileExpiredOrder(expiredOrder))
                .thenReturn(ReconciliationResult.unpaidOrFailed("Verified terminal failure at gateway"));
        when(failingJdbcTemplate.queryForList(
                eq("SELECT voucher_id FROM order_vouchers WHERE order_id = ?"),
                eq(Long.class),
                eq(109L)
        )).thenReturn(List.of(77L));
        when(failingJdbcTemplate.update(
                eq("DELETE FROM order_vouchers WHERE order_id = ?"),
                eq(109L)
        )).thenThrow(new DataIntegrityViolationException("Simulated MySQL deadlock/constraint error during voucher release"));

        assertThatThrownBy(daemon::cleanExpiredPendingPaymentOrders)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Simulated MySQL deadlock/constraint error");

        verify(auditLogService, never()).record(any(), eq("VNPAY_TIMEOUT_CANCELLED_RESTOCK"), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("CRON-01: cleanExpiredPendingPaymentOrders() should settle order as PAID without cancelling or restocking if gateway reconciliation proves payment succeeded")
    void cron01_VerifiedPaidDuringReconciliation_AdvancesToPaidWithoutRestocking() {
        VNPayConfig config = new VNPayConfig();
        config.setExpireMinutes(15);

        VnpayTimeoutCleanerDaemon daemon = new VnpayTimeoutCleanerDaemon(
                orderRepository,
                config,
                reconciliationClient,
                stockMutationService,
                orderVoucherReleasePort,
                auditLogService,
                objectMapper
        );

        User customer = User.builder().id(15L).build();
        Order expiredOrder = Order.builder()
                .id(102L)
                .user(customer)
                .orderCode("ORD-EXP-102")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .stockDeductedAt(LocalDateTime.now().minusMinutes(18))
                .build();

        when(orderRepository.findExpiredPendingPaymentOrders(any())).thenReturn(List.of(expiredOrder));
        when(orderRepository.findByIdWithLock(102L)).thenReturn(Optional.of(expiredOrder));
        when(reconciliationClient.reconcileExpiredOrder(expiredOrder))
                .thenReturn(ReconciliationResult.paid("20261016101500", "VCB", "Settled at gateway"));

        int cleaned = daemon.cleanExpiredPendingPaymentOrders();

        assertThat(cleaned).isZero();
        assertThat(expiredOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(expiredOrder.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        assertThat(expiredOrder.getVnpayBankCode()).isEqualTo("VCB");
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
        verify(stockMutationService, never()).deductOrder(anyLong());
    }

    @Test
    @DisplayName("F01: cleanExpiredPendingPaymentOrders() must skip order without cancelling or restocking when reconciliation is INDETERMINATE")
    void cron01_IndeterminateReconciliation_LeavesOrderAndStockUntouched() {
        VNPayConfig config = new VNPayConfig();
        config.setExpireMinutes(15);

        VnpayTimeoutCleanerDaemon daemon = new VnpayTimeoutCleanerDaemon(
                orderRepository,
                config,
                reconciliationClient,
                stockMutationService,
                orderVoucherReleasePort,
                auditLogService,
                objectMapper
        );

        Order expiredOrder = Order.builder()
                .id(103L)
                .user(User.builder().id(18L).build())
                .orderCode("ORD-EXP-103")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .stockDeductedAt(LocalDateTime.now().minusMinutes(25))
                .build();

        when(orderRepository.findExpiredPendingPaymentOrders(any())).thenReturn(List.of(expiredOrder));
        when(orderRepository.findByIdWithLock(103L)).thenReturn(Optional.of(expiredOrder));
        when(reconciliationClient.reconcileExpiredOrder(expiredOrder))
                .thenReturn(ReconciliationResult.indeterminate("Gateway timeout during QueryDR"));

        int cleaned = daemon.cleanExpiredPendingPaymentOrders();

        assertThat(cleaned).isZero();
        assertThat(expiredOrder.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_PAYMENT);
        assertThat(expiredOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.UNPAID);
        verify(orderRepository, never()).save(any());
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
        verify(orderVoucherReleasePort, never()).releaseOrderVoucherIfPresent(anyLong());
    }

    @Test
    @DisplayName("F02 (Interleaving A): refreshing lockedOrder under PESSIMISTIC_WRITE after concurrent IPN cancel prevents double stock restoration")
    void cron01_ConcurrentIpnCancelCommittedBeforeLock_RefreshesEntityAndSkipsSecondRestock() {
        VNPayConfig config = new VNPayConfig();
        config.setExpireMinutes(15);

        EntityManager entityManager = mock(EntityManager.class);
        VnpayTimeoutCleanerDaemon daemon = new VnpayTimeoutCleanerDaemon(
                orderRepository,
                config,
                reconciliationClient,
                stockMutationService,
                orderVoucherReleasePort,
                auditLogService,
                objectMapper
        );
        daemon.setEntityManager(entityManager);

        Order staleCandidate = Order.builder()
                .id(104L)
                .user(User.builder().id(20L).build())
                .orderCode("ORD-RACE-CANCEL")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .stockDeductedAt(LocalDateTime.now().minusMinutes(16))
                .stockRestoredAt(null)
                .build();

        when(orderRepository.findExpiredPendingPaymentOrders(any())).thenReturn(List.of(staleCandidate));
        when(orderRepository.findByIdWithLock(104L)).thenReturn(Optional.of(staleCandidate));

        doAnswer(invocation -> {
            Order refreshed = invocation.getArgument(0);
            refreshed.setOrderStatus(Order.OrderStatus.CANCELLED);
            refreshed.setStockRestoredAt(LocalDateTime.now());
            refreshed.setStockRestoreReason(Order.StockRestoreReason.CANCELLED);
            return null;
        }).when(entityManager).refresh(staleCandidate, LockModeType.PESSIMISTIC_WRITE);

        int cleaned = daemon.cleanExpiredPendingPaymentOrders();

        assertThat(cleaned).isZero();
        verify(entityManager).refresh(staleCandidate, LockModeType.PESSIMISTIC_WRITE);
        verify(reconciliationClient, never()).reconcileExpiredOrder(any());
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
        verify(orderVoucherReleasePort, never()).releaseOrderVoucherIfPresent(anyLong());
    }

    @Test
    @DisplayName("F02 (Interleaving B): refreshing lockedOrder under PESSIMISTIC_WRITE after concurrent IPN PAID prevents overwriting PAID with CANCELLED")
    void cron01_ConcurrentIpnPaidCommittedBeforeLock_RefreshesEntityAndPreservesPaidState() {
        VNPayConfig config = new VNPayConfig();
        config.setExpireMinutes(15);

        EntityManager entityManager = mock(EntityManager.class);
        VnpayTimeoutCleanerDaemon daemon = new VnpayTimeoutCleanerDaemon(
                orderRepository,
                config,
                reconciliationClient,
                stockMutationService,
                orderVoucherReleasePort,
                auditLogService,
                objectMapper
        );
        daemon.setEntityManager(entityManager);

        Order staleCandidate = Order.builder()
                .id(105L)
                .user(User.builder().id(21L).build())
                .orderCode("ORD-RACE-PAID")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .stockDeductedAt(LocalDateTime.now().minusMinutes(16))
                .build();

        when(orderRepository.findExpiredPendingPaymentOrders(any())).thenReturn(List.of(staleCandidate));
        when(orderRepository.findByIdWithLock(105L)).thenReturn(Optional.of(staleCandidate));

        doAnswer(invocation -> {
            Order refreshed = invocation.getArgument(0);
            refreshed.setOrderStatus(Order.OrderStatus.PENDING_CONFIRMATION);
            refreshed.setPaymentStatus(Order.PaymentStatus.PAID);
            refreshed.setVnpayPayDate("20261024101500");
            return null;
        }).when(entityManager).refresh(staleCandidate, LockModeType.PESSIMISTIC_WRITE);

        int cleaned = daemon.cleanExpiredPendingPaymentOrders();

        assertThat(cleaned).isZero();
        assertThat(staleCandidate.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(staleCandidate.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        verify(entityManager).refresh(staleCandidate, LockModeType.PESSIMISTIC_WRITE);
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
    }

    // =========================================================================
    // F01 & F09: Official 15-Field VNPay QueryDR Signature & Outcome Tests
    // =========================================================================

    @Test
    @DisplayName("F01: DefaultVNPayGatewayReconciliationClient must return INDETERMINATE (never VERIFIED_UNPAID_OR_FAILED) when VNPay config is missing")
    void defaultClient_MissingConfig_ReturnsIndeterminateInsteadOfAssumingUnpaid() {
        VNPayConfig unconfigured = new VNPayConfig();
        DefaultVNPayGatewayReconciliationClient client =
                new DefaultVNPayGatewayReconciliationClient(unconfigured, objectMapper);

        Order expiredOrder = Order.builder()
                .id(201L)
                .orderCode("ORD-NO-CFG-201")
                .vnpayTxnRef("ORD-NO-CFG-201")
                .vnpayCreateDate("20261024100000")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .vnpayPayDate(null)
                .build();
        expiredOrder.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0, 0));

        ReconciliationResult result = client.reconcileExpiredOrder(expiredOrder);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INDETERMINATE);
    }

    @Test
    @DisplayName("F09: DefaultVNPayGatewayReconciliationClient should return VERIFIED_PAID when QueryDR returns spec-signed 15-field 00/00 with non-empty vnp_OrderInfo")
    void defaultClient_QueryDrReturnsSignedPaidWithOrderInfo_ReturnsVerifiedPaid() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String apiUrl = startQueryDrStubServer(200, requestJson -> {
            capturedBody.set(requestJson);
            return buildIndependentSpecSignedQueryDrJson(
                    "RSP-PAID-202",
                    "querydr",
                    "00",
                    "Truy van thanh cong",
                    "AURELIA01",
                    "ORD-QDR-202",
                    "25000000",
                    "VCB",
                    "20261024100500",
                    "14599901",
                    "01",
                    "00",
                    "Thanh toan don hang ORD-QDR-202",
                    "",
                    "",
                    TEST_SECRET
            );
        });

        VNPayConfig config = configuredForQueryDr(apiUrl);
        DefaultVNPayGatewayReconciliationClient client =
                new DefaultVNPayGatewayReconciliationClient(config, objectMapper);

        Order expiredOrder = Order.builder()
                .id(202L)
                .orderCode("ORD-QDR-202")
                .vnpayTxnRef("ORD-QDR-202")
                .vnpayCreateDate("20261024100000")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .build();
        expiredOrder.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0, 0));

        ReconciliationResult result = client.reconcileExpiredOrder(expiredOrder);

        assertThat(capturedBody.get()).contains("\"vnp_Command\":\"querydr\"");
        assertThat(capturedBody.get()).contains("\"vnp_TxnRef\":\"ORD-QDR-202\"");
        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.VERIFIED_PAID);
        assertThat(result.bankCode()).isEqualTo("VCB");
        assertThat(result.payDate()).isEqualTo("20261024100500");
    }

    @Test
    @DisplayName("F09: DefaultVNPayGatewayReconciliationClient should accept spec-signed 15-field 00/00 when optional vnp_OrderInfo is empty")
    void defaultClient_QueryDrReturnsSignedPaidWithEmptyOrderInfo_ReturnsVerifiedPaid() throws Exception {
        String apiUrl = startQueryDrStubServer(200, requestJson ->
                buildIndependentSpecSignedQueryDrJson(
                        "RSP-EMPTY-INFO",
                        "querydr",
                        "00",
                        "Success",
                        "AURELIA01",
                        "ORD-QDR-EMPTY-INFO",
                        "25000000",
                        "NCB",
                        "20261024100600",
                        "14599902",
                        "01",
                        "00",
                        "",
                        "",
                        "",
                        TEST_SECRET
                )
        );

        DefaultVNPayGatewayReconciliationClient client =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrl), objectMapper);

        Order expiredOrder = Order.builder()
                .id(2021L)
                .orderCode("ORD-QDR-EMPTY-INFO")
                .vnpayTxnRef("ORD-QDR-EMPTY-INFO")
                .vnpayCreateDate("20261024100100")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .build();

        ReconciliationResult result = client.reconcileExpiredOrder(expiredOrder);
        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.VERIFIED_PAID);
    }

    @Test
    @DisplayName("F09: DefaultVNPayGatewayReconciliationClient should return VERIFIED_UNPAID_OR_FAILED when QueryDR returns spec-signed 15-field 00/02")
    void defaultClient_QueryDrReturnsSignedTerminalFailure_ReturnsVerifiedUnpaidOrFailed() throws Exception {
        String apiUrl = startQueryDrStubServer(200, requestJson ->
                buildIndependentSpecSignedQueryDrJson(
                        "RSP-FAIL-203",
                        "querydr",
                        "00",
                        "Truy van thanh cong",
                        "AURELIA01",
                        "ORD-QDR-203",
                        "18000000",
                        "NCB",
                        "20261024100400",
                        "14599903",
                        "01",
                        "02",
                        "Thanh toan don hang ORD-QDR-203",
                        "",
                        "",
                        TEST_SECRET
                )
        );

        VNPayConfig config = configuredForQueryDr(apiUrl);
        DefaultVNPayGatewayReconciliationClient client =
                new DefaultVNPayGatewayReconciliationClient(config, objectMapper);

        Order expiredOrder = Order.builder()
                .id(203L)
                .orderCode("ORD-QDR-203")
                .vnpayTxnRef("ORD-QDR-203")
                .vnpayCreateDate("20261024100000")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .build();
        expiredOrder.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0, 0));

        ReconciliationResult result = client.reconcileExpiredOrder(expiredOrder);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.VERIFIED_UNPAID_OR_FAILED);
    }

    @Test
    @DisplayName("F09: DefaultVNPayGatewayReconciliationClient should return VERIFIED_UNPAID_OR_FAILED when QueryDR returns spec-signed vnp_ResponseCode=91 (customer closed tab without paying)")
    void defaultClient_QueryDrReturnsSignedCode91TransactionNotFound_ReturnsVerifiedUnpaidOrFailed() throws Exception {
        String apiUrl = startQueryDrStubServer(200, requestJson ->
                buildIndependentSpecSignedQueryDrJson(
                        "RSP-NOTFOUND-2031",
                        "querydr",
                        "91",
                        "Khong tim thay giao dich yeu cau",
                        "AURELIA01",
                        "ORD-QDR-2031",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "QueryDR order ORD-QDR-2031",
                        "",
                        "",
                        TEST_SECRET
                )
        );

        VNPayConfig config = configuredForQueryDr(apiUrl);
        DefaultVNPayGatewayReconciliationClient client =
                new DefaultVNPayGatewayReconciliationClient(config, objectMapper);

        Order expiredOrder = Order.builder()
                .id(2031L)
                .orderCode("ORD-QDR-2031")
                .vnpayTxnRef("ORD-QDR-2031")
                .vnpayCreateDate("20261024100000")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .build();
        expiredOrder.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0, 0));

        ReconciliationResult result = client.reconcileExpiredOrder(expiredOrder);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.VERIFIED_UNPAID_OR_FAILED);
        assertThat(result.diagnosticMessage()).contains("vnp_ResponseCode=91");
    }

    private Order unpaidExpiredOrder(String orderCode) {
        Order order = Order.builder()
                .id(4100L)
                .orderCode(orderCode)
                .vnpayTxnRef(orderCode)
                .vnpayCreateDate("20261024100000")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .build();
        order.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0, 0));
        return order;
    }

    @Test
    @DisplayName("Phase 4: QueryDR request is signed with the pipe-delimited 9-field formula in the VNPay-specified order")
    void defaultClient_QueryDrRequest_UsesSpecPipeDelimitedSignatureAndGmt7Timestamps() throws Exception {
        AtomicReference<String> capturedRequest = new AtomicReference<>();
        String apiUrl = startQueryDrStubServer(200, requestJson -> {
            capturedRequest.set(requestJson);
            return "{}";
        });
        DefaultVNPayGatewayReconciliationClient client =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrl), objectMapper);

        client.reconcileExpiredOrder(unpaidExpiredOrder("ORD-QDR-4100"));

        JsonNode request = objectMapper.readTree(capturedRequest.get());
        String expectedHashData = String.join("|",
                request.get("vnp_RequestId").asText(),
                request.get("vnp_Version").asText(),
                request.get("vnp_Command").asText(),
                request.get("vnp_TmnCode").asText(),
                request.get("vnp_TxnRef").asText(),
                request.get("vnp_TransactionDate").asText(),
                request.get("vnp_CreateDate").asText(),
                request.get("vnp_IpAddr").asText(),
                request.get("vnp_OrderInfo").asText());
        assertThat(request.get("vnp_SecureHash").asText())
                .isEqualTo(VNPayUtil.hmacSHA512(TEST_SECRET, expectedHashData));
        assertThat(request.get("vnp_Command").asText()).isEqualTo("querydr");
        assertThat(request.get("vnp_TransactionDate").asText()).isEqualTo("20261024100000");
        assertThat(request.get("vnp_CreateDate").asText()).matches("\\d{14}");
    }

    @Test
    @DisplayName("Phase 4: gateway-side error codes 97/99 are INDETERMINATE and never prove the order unpaid")
    void defaultClient_GatewayErrorCodes97And99_AreIndeterminate() throws Exception {
        for (String errorCode : List.of("97", "99")) {
            String apiUrl = startQueryDrStubServer(200, requestJson ->
                    buildIndependentSpecSignedQueryDrJson(
                            "RSP-ERR-" + errorCode, "querydr", errorCode, "Gateway error",
                            "AURELIA01", "ORD-QDR-4100", "", "", "", "", "", "",
                            "QueryDR order ORD-QDR-4100", "", "", TEST_SECRET));
            DefaultVNPayGatewayReconciliationClient client =
                    new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrl), objectMapper);

            ReconciliationResult result = client.reconcileExpiredOrder(unpaidExpiredOrder("ORD-QDR-4100"));

            assertThat(result.outcome()).as("vnp_ResponseCode=" + errorCode)
                    .isEqualTo(ReconciliationOutcome.INDETERMINATE);
            stopStubServer();
        }
    }

    @Test
    @DisplayName("Phase 4: HTTP 5xx from the gateway is INDETERMINATE")
    void defaultClient_Gateway500_IsIndeterminate() throws Exception {
        String apiUrl = startQueryDrStubServer(500, requestJson -> "{}");
        DefaultVNPayGatewayReconciliationClient client =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrl), objectMapper);

        assertThat(client.reconcileExpiredOrder(unpaidExpiredOrder("ORD-QDR-4100")).outcome())
                .isEqualTo(ReconciliationOutcome.INDETERMINATE);
    }

    @Test
    @DisplayName("Phase 4: unreachable gateway (network failure) is INDETERMINATE")
    void defaultClient_ConnectionRefused_IsIndeterminate() throws Exception {
        String apiUrl = startQueryDrStubServer(200, requestJson -> "{}");
        stopStubServer(); // port is now closed
        DefaultVNPayGatewayReconciliationClient client =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrl), objectMapper);

        assertThat(client.reconcileExpiredOrder(unpaidExpiredOrder("ORD-QDR-4100")).outcome())
                .isEqualTo(ReconciliationOutcome.INDETERMINATE);
    }
    @Test
    @DisplayName("F09: verifyQueryDrResponseSignature and reconcileExpiredOrder must reject 14-field signature missing vnp_OrderInfo, URL-query sorted fallback signature, tampered OrderInfo, or amount/reference mismatch")
    void defaultClient_InvalidSignatureFormulasOrTamperedFields_ReturnIndeterminate() throws Exception {
        Order expiredOrder = Order.builder()
                .id(204L)
                .orderCode("ORD-QDR-204")
                .vnpayTxnRef("ORD-QDR-204")
                .vnpayCreateDate("20261024100000")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .build();
        expiredOrder.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0, 0));

        // 1. Legacy 14-field signature omitting vnp_OrderInfo when vnp_OrderInfo is present -> must be rejected
        Map<String, String> legacy14FieldMap = new LinkedHashMap<>();
        legacy14FieldMap.put("vnp_ResponseId", "RSP-14");
        legacy14FieldMap.put("vnp_Command", "querydr");
        legacy14FieldMap.put("vnp_ResponseCode", "00");
        legacy14FieldMap.put("vnp_Message", "OK");
        legacy14FieldMap.put("vnp_TmnCode", "AURELIA01");
        legacy14FieldMap.put("vnp_TxnRef", "ORD-QDR-204");
        legacy14FieldMap.put("vnp_Amount", "18000000");
        legacy14FieldMap.put("vnp_BankCode", "NCB");
        legacy14FieldMap.put("vnp_PayDate", "20261024100400");
        legacy14FieldMap.put("vnp_TransactionNo", "14599904");
        legacy14FieldMap.put("vnp_TransactionType", "01");
        legacy14FieldMap.put("vnp_TransactionStatus", "00");
        legacy14FieldMap.put("vnp_OrderInfo", "Thanh toan don hang ORD-QDR-204");
        legacy14FieldMap.put("vnp_PromotionCode", "");
        legacy14FieldMap.put("vnp_PromotionAmount", "");
        String wrong14FieldPipe = "RSP-14|querydr|00|OK|AURELIA01|ORD-QDR-204|18000000|NCB|20261024100400|14599904|01|00||";
        legacy14FieldMap.put("vnp_SecureHash", VNPayUtil.hmacSHA512(TEST_SECRET, wrong14FieldPipe));
        assertThat(DefaultVNPayGatewayReconciliationClient.verifyQueryDrResponseSignature(legacy14FieldMap, TEST_SECRET))
                .isFalse();

        // 2. Non-spec URL-query sorted signature (PAY/IPN formula) -> must be rejected (no fallback allowed)
        Map<String, String> urlSortedSignedMap = new TreeMap<>(legacy14FieldMap);
        urlSortedSignedMap.remove("vnp_SecureHash");
        String urlQueryHash = VNPayUtil.hmacSHA512(TEST_SECRET, VNPayUtil.buildHashData(urlSortedSignedMap));
        urlSortedSignedMap.put("vnp_SecureHash", urlQueryHash);
        assertThat(DefaultVNPayGatewayReconciliationClient.verifyQueryDrResponseSignature(urlSortedSignedMap, TEST_SECRET))
                .isFalse();

        // 3. Tampered vnp_OrderInfo after signing with 15-field spec formula -> INDETERMINATE
        String apiUrlTamperedInfo = startQueryDrStubServer(200, requestJson -> {
            String validJson = buildIndependentSpecSignedQueryDrJson(
                    "RSP-TAMP", "querydr", "00", "OK", "AURELIA01", "ORD-QDR-204",
                    "18000000", "NCB", "20261024100400", "14599905", "01", "00",
                    "Original OrderInfo", "", "", TEST_SECRET
            );
            return validJson.replace("Original OrderInfo", "Tampered OrderInfo");
        });
        DefaultVNPayGatewayReconciliationClient tamperedClient =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrlTamperedInfo), objectMapper);
        assertThat(tamperedClient.reconcileExpiredOrder(expiredOrder).outcome())
                .isEqualTo(ReconciliationOutcome.INDETERMINATE);
        stopStubServer();

        // 4. Signed response with mismatched vnp_Amount -> INDETERMINATE
        String apiUrlWrongAmount = startQueryDrStubServer(200, requestJson ->
                buildIndependentSpecSignedQueryDrJson(
                        "RSP-AMT", "querydr", "00", "OK", "AURELIA01", "ORD-QDR-204",
                        "99900000", "NCB", "20261024100400", "14599906", "01", "00",
                        "Thanh toan don hang ORD-QDR-204", "", "", TEST_SECRET
                )
        );
        DefaultVNPayGatewayReconciliationClient wrongAmountClient =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrlWrongAmount), objectMapper);
        assertThat(wrongAmountClient.reconcileExpiredOrder(expiredOrder).outcome())
                .isEqualTo(ReconciliationOutcome.INDETERMINATE);
        stopStubServer();

        // 5. Signed response with mismatched vnp_TxnRef -> INDETERMINATE
        String apiUrlWrongRef = startQueryDrStubServer(200, requestJson ->
                buildIndependentSpecSignedQueryDrJson(
                        "RSP-REF", "querydr", "00", "OK", "AURELIA01", "OTHER-ORDER-REF",
                        "18000000", "NCB", "20261024100400", "14599907", "01", "00",
                        "Thanh toan don hang OTHER", "", "", TEST_SECRET
                )
        );
        DefaultVNPayGatewayReconciliationClient wrongRefClient =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrlWrongRef), objectMapper);
        assertThat(wrongRefClient.reconcileExpiredOrder(expiredOrder).outcome())
                .isEqualTo(ReconciliationOutcome.INDETERMINATE);
        stopStubServer();

        // 6. Incomplete transaction status (00 / 01), not found (91), HTTP 500 -> INDETERMINATE
        String apiUrlPending = startQueryDrStubServer(200, requestJson ->
                buildIndependentSpecSignedQueryDrJson(
                        "RSP-01", "querydr", "00", "OK", "AURELIA01", "ORD-QDR-204",
                        "18000000", "NCB", "20261024100400", "14599908", "01", "01",
                        "Thanh toan don hang ORD-QDR-204", "", "", TEST_SECRET
                )
        );
        DefaultVNPayGatewayReconciliationClient pendingClient =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrlPending), objectMapper);
        assertThat(pendingClient.reconcileExpiredOrder(expiredOrder).outcome())
                .isEqualTo(ReconciliationOutcome.INDETERMINATE);
        stopStubServer();
    }

    // =========================================================================
    // F10: Consistent PAY vnp_CreateDate vs QueryDR vnp_TransactionDate Tests
    // =========================================================================

    @Test
    @DisplayName("F10: when order is created at 21:55 and PAY is opened at 22:00, QueryDR vnp_TransactionDate must equal PAY vnp_CreateDate (20261024220000)")
    void f10_DelayedPayInitiation_QueryDrTransactionDateMatchesPayCreateDateExactly() throws Exception {
        AtomicReference<String> capturedQueryDrRequest = new AtomicReference<>();
        String apiUrl = startQueryDrStubServer(200, requestJson -> {
            capturedQueryDrRequest.set(requestJson);
            return buildIndependentSpecSignedQueryDrJson(
                    "RSP-F10-DELAY", "querydr", "00", "OK", "AURELIA01", "ORD-F10-2155",
                    "25000000", "VCB", "20261024220100", "14599910", "01", "00",
                    "Thanh toan don hang ORD-F10-2155", "", "", TEST_SECRET
            );
        });

        VNPayConfig config = configuredForQueryDr(apiUrl);
        config.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        config.setReturnUrl("http://localhost:8080/payment/vnpay-return");
        config.setCommand("pay");
        config.setOrderType("other");

        VNPayServiceImpl vnPayService = new VNPayServiceImpl(
                config,
                orderRepository,
                stockMutationService,
                orderVoucherReleasePort,
                auditLogService,
                objectMapper
        );
        DefaultVNPayGatewayReconciliationClient queryDrClient =
                new DefaultVNPayGatewayReconciliationClient(config, objectMapper);

        User owner = User.builder().id(1L).email("owner@aureliabook.vn").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        );

        LocalDateTime orderCreatedAt = LocalDateTime.of(2026, 10, 24, 21, 55, 0);
        LocalDateTime payOpenedAt = LocalDateTime.of(2026, 10, 24, 22, 0, 0);

        Order order = Order.builder()
                .id(301L)
                .user(owner)
                .orderCode("ORD-F10-2155")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(orderCreatedAt)
                .build();
        order.setCreatedAt(orderCreatedAt);

        when(orderRepository.findByIdWithLock(301L)).thenReturn(Optional.of(order));

        // Customer opens PAY at 22:00 (5 minutes after order creation at 21:55)
        vnPayService.setClock(Clock.fixed(payOpenedAt.atZone(VNPayServiceImpl.VIETNAM_ZONE).toInstant(), VNPayServiceImpl.VIETNAM_ZONE));
        String payUrl = vnPayService.createPaymentUrl(1L, 301L, "127.0.0.1");

        assertThat(payUrl).contains("vnp_CreateDate=20261024220000");
        assertThat(payUrl).contains("vnp_ExpireDate=20261024221000");

        // Reconcile via QueryDR
        ReconciliationResult result = queryDrClient.reconcileExpiredOrder(order);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.VERIFIED_PAID);
        JsonNode sentJson = objectMapper.readTree(capturedQueryDrRequest.get());
        assertThat(sentJson.path("vnp_TransactionDate").asText()).isEqualTo("20261024220000");
    }

    @Test
    @DisplayName("F10: when customer retries PAY within 15-minute window, QueryDR vnp_TransactionDate must match the retry PAY vnp_CreateDate while keeping deadline unchanged")
    void f10_RetryPayInitiationWithinWindow_QueryDrUsesLatestRetryCreateDate() throws Exception {
        AtomicReference<String> capturedQueryDrRequest = new AtomicReference<>();
        String apiUrl = startQueryDrStubServer(200, requestJson -> {
            capturedQueryDrRequest.set(requestJson);
            return buildIndependentSpecSignedQueryDrJson(
                    "RSP-F10-RETRY", "querydr", "00", "OK", "AURELIA01", "ORD-F10-RETRY-R101200",
                    "25000000", "NCB", "20261024101300", "14599911", "01", "02",
                    "Thanh toan don hang ORD-F10-RETRY", "", "", TEST_SECRET
            );
        });

        VNPayConfig config = configuredForQueryDr(apiUrl);
        config.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        config.setReturnUrl("http://localhost:8080/payment/vnpay-return");
        config.setCommand("pay");
        config.setOrderType("other");

        VNPayServiceImpl vnPayService = new VNPayServiceImpl(
                config,
                orderRepository,
                stockMutationService,
                orderVoucherReleasePort,
                auditLogService,
                objectMapper
        );
        DefaultVNPayGatewayReconciliationClient queryDrClient =
                new DefaultVNPayGatewayReconciliationClient(config, objectMapper);

        User owner = User.builder().id(1L).email("owner@aureliabook.vn").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        );

        LocalDateTime orderCreatedAt = LocalDateTime.of(2026, 10, 24, 10, 0, 0);
        Order order = Order.builder()
                .id(302L)
                .user(owner)
                .orderCode("ORD-F10-RETRY")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(orderCreatedAt)
                .build();
        order.setCreatedAt(orderCreatedAt);

        when(orderRepository.findByIdWithLock(302L)).thenReturn(Optional.of(order));

        // First PAY initiation at 10:03
        vnPayService.setClock(Clock.fixed(orderCreatedAt.plusMinutes(3).atZone(VNPayServiceImpl.VIETNAM_ZONE).toInstant(), VNPayServiceImpl.VIETNAM_ZONE));
        String firstPayUrl = vnPayService.createPaymentUrl(1L, 302L, "127.0.0.1");
        assertThat(firstPayUrl).contains("vnp_CreateDate=20261024100300");
        assertThat(firstPayUrl).contains("vnp_ExpireDate=20261024101500");

        // Retry PAY initiation at 10:12 (still within the 10:15 deadline)
        vnPayService.setClock(Clock.fixed(orderCreatedAt.plusMinutes(12).atZone(VNPayServiceImpl.VIETNAM_ZONE).toInstant(), VNPayServiceImpl.VIETNAM_ZONE));
        String retryPayUrl = vnPayService.createPaymentUrl(1L, 302L, "127.0.0.1");
        assertThat(retryPayUrl).contains("vnp_CreateDate=20261024101200");
        assertThat(retryPayUrl).contains("vnp_ExpireDate=20261024101500");

        // Verify that the unpaid order still exposes getVnpayPayDate() == null, while the physical DB column holds "INIT:20261024101200"
        assertThat(order.getVnpayPayDate()).isNull();
        java.lang.reflect.Field rawPayDateField = Order.class.getDeclaredField("vnpayPayDate");
        rawPayDateField.setAccessible(true);
        String persistedDbColumnValue = (String) rawPayDateField.get(order);
        assertThat(persistedDbColumnValue).isEqualTo("INIT:20261024101200");

        // Simulate JVM B after restart loading the Order row from MySQL (only DB columns populated, no in-memory state)
        Order reloadedInNewJvmFromDb = new Order();
        reloadedInNewJvmFromDb.setId(302L);
        reloadedInNewJvmFromDb.setUser(owner);
        reloadedInNewJvmFromDb.setOrderCode("ORD-F10-RETRY");
        reloadedInNewJvmFromDb.setVnpayTxnRef(order.getVnpayTxnRef());
        rawPayDateField.set(reloadedInNewJvmFromDb, persistedDbColumnValue);
        reloadedInNewJvmFromDb.setPaymentMethod(Order.PaymentMethod.VNPAY);
        reloadedInNewJvmFromDb.setOrderStatus(Order.OrderStatus.PENDING_PAYMENT);
        reloadedInNewJvmFromDb.setPaymentStatus(Order.PaymentStatus.UNPAID);
        reloadedInNewJvmFromDb.setFinalTotalAmount(new BigDecimal("250000.00"));
        reloadedInNewJvmFromDb.setStockDeductedAt(orderCreatedAt);
        reloadedInNewJvmFromDb.setCreatedAt(orderCreatedAt);

        assertThat(reloadedInNewJvmFromDb.getVnpayPayDate()).isNull();
        assertThat(reloadedInNewJvmFromDb.getVnpayCreateDate()).isEqualTo("20261024101200");

        ReconciliationResult result = queryDrClient.reconcileExpiredOrder(reloadedInNewJvmFromDb);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.VERIFIED_UNPAID_OR_FAILED);
        JsonNode sentJson = objectMapper.readTree(capturedQueryDrRequest.get());
        assertThat(sentJson.path("vnp_TransactionDate").asText()).isEqualTo("20261024101200");
    }

    @Test
    @DisplayName("F10: when PAY initiation metadata (vnpayTxnRef or vnp_CreateDate) is missing, QueryDR must return INDETERMINATE without guessing order.createdAt or concluding failure")
    void f10_MissingPayInitiationMetadata_ReturnsIndeterminateWithoutGuessingTimestamp() throws Exception {
        AtomicInteger httpCallCount = new AtomicInteger(0);
        String apiUrl = startQueryDrStubServer(200, requestJson -> {
            httpCallCount.incrementAndGet();
            // Even if gateway stub returns signed 00/02 terminal failure, client must never call it with a guessed timestamp
            return buildIndependentSpecSignedQueryDrJson(
                    "RSP-F10-GUESS", "querydr", "00", "OK", "AURELIA01", "ORD-F10-MISSING-META",
                    "25000000", "NCB", "20261024215500", "14599912", "01", "02",
                    "QueryDR order ORD-F10-MISSING-META", "", "", TEST_SECRET
            );
        });

        DefaultVNPayGatewayReconciliationClient queryDrClient =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrl), objectMapper);

        // Case 1: Order created at 21:55, PAY never initiated (vnpayTxnRef is null, vnpayCreateDate is null)
        Order uninitiatedOrder = Order.builder()
                .id(303L)
                .orderCode("ORD-F10-UNINITIATED")
                .vnpayTxnRef(null)
                .vnpayCreateDate(null)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .build();
        uninitiatedOrder.setCreatedAt(LocalDateTime.of(2026, 10, 24, 21, 55, 0));

        ReconciliationResult res1 = queryDrClient.reconcileExpiredOrder(uninitiatedOrder);
        assertThat(res1.outcome()).isEqualTo(ReconciliationOutcome.INDETERMINATE);

        // Case 2: Order has vnpayTxnRef but is missing PAY initiation timestamp (vnpayCreateDate is null)
        Order missingTimestampOrder = Order.builder()
                .id(304L)
                .orderCode("ORD-F10-MISSING-META")
                .vnpayTxnRef("ORD-F10-MISSING-META")
                .vnpayCreateDate(null)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .build();
        missingTimestampOrder.setCreatedAt(LocalDateTime.of(2026, 10, 24, 21, 55, 0));

        ReconciliationResult res2 = queryDrClient.reconcileExpiredOrder(missingTimestampOrder);
        assertThat(res2.outcome()).isEqualTo(ReconciliationOutcome.INDETERMINATE);
        assertThat(httpCallCount.get()).isZero();
    }

    @Test
    @DisplayName("CRON-01 / F10: when latest retry returns 91 (not found) but an earlier attempt was PAID (00/00), CRON-01 must reconcile earlier attempts and mark order PAID without cancelling or restocking")
    void f10_LatestRetryReturns91WhileEarlierAttemptPaid_ReconcilesAsPaidWithoutCancellingOrRestocking() throws Exception {
        String apiUrl = startQueryDrStubServer(200, requestJson -> {
            JsonNode reqNode = objectMapper.readTree(requestJson);
            String queriedRef = reqNode.path("vnp_TxnRef").asText();
            String queriedDate = reqNode.path("vnp_TransactionDate").asText();
            if ("ORD-MULTI-305-R101200".equals(queriedRef) && "20261024101200".equals(queriedDate)) {
                return buildIndependentSpecSignedQueryDrJson(
                        "RSP-MULTI-91", "querydr", "91", "Khong tim thay giao dich yeu cau",
                        "AURELIA01", "ORD-MULTI-305-R101200", "", "", "", "", "", "",
                        "QueryDR order ORD-MULTI-305", "", "", TEST_SECRET
                );
            }
            if ("ORD-MULTI-305".equals(queriedRef) && "20261024100300".equals(queriedDate)) {
                return buildIndependentSpecSignedQueryDrJson(
                        "RSP-MULTI-PAID", "querydr", "00", "OK",
                        "AURELIA01", "ORD-MULTI-305", "25000000", "VCB", "20261024100400",
                        "14599950", "01", "00", "QueryDR order ORD-MULTI-305", "", "", TEST_SECRET
                );
            }
            return "{}";
        });

        VNPayConfig config = configuredForQueryDr(apiUrl);
        config.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        config.setReturnUrl("http://localhost:8080/payment/vnpay-return");
        config.setCommand("pay");
        config.setOrderType("other");

        VNPayServiceImpl vnPayService = new VNPayServiceImpl(
                config,
                orderRepository,
                stockMutationService,
                orderVoucherReleasePort,
                auditLogService,
                objectMapper
        );
        DefaultVNPayGatewayReconciliationClient queryDrClient =
                new DefaultVNPayGatewayReconciliationClient(config, objectMapper);
        VnpayTimeoutCleanerDaemon daemon = new VnpayTimeoutCleanerDaemon(
                orderRepository,
                config,
                queryDrClient,
                stockMutationService,
                orderVoucherReleasePort,
                auditLogService,
                objectMapper
        );

        User owner = User.builder().id(1L).email("owner@aureliabook.vn").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        );

        LocalDateTime orderCreatedAt = LocalDateTime.of(2026, 10, 24, 10, 0, 0);
        Order order = Order.builder()
                .id(305L)
                .user(owner)
                .orderCode("ORD-MULTI-305")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(orderCreatedAt)
                .build();
        order.setCreatedAt(orderCreatedAt);

        when(orderRepository.findByIdWithLock(305L)).thenReturn(Optional.of(order));

        // Tab 1 opened at 10:03 (customer pays here, IPN delayed)
        vnPayService.setClock(Clock.fixed(orderCreatedAt.plusMinutes(3).atZone(VNPayServiceImpl.VIETNAM_ZONE).toInstant(), VNPayServiceImpl.VIETNAM_ZONE));
        vnPayService.createPaymentUrl(1L, 305L, "127.0.0.1");

        // Tab 2 opened at 10:12 (customer closes tab without paying -> QueryDR returns 91)
        vnPayService.setClock(Clock.fixed(orderCreatedAt.plusMinutes(12).atZone(VNPayServiceImpl.VIETNAM_ZONE).toInstant(), VNPayServiceImpl.VIETNAM_ZONE));
        vnPayService.createPaymentUrl(1L, 305L, "127.0.0.1");

        when(orderRepository.findExpiredPendingPaymentOrders(any())).thenReturn(List.of(order));

        int cancelled = daemon.cleanExpiredPendingPaymentOrders();

        assertThat(cancelled).isZero();
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(order.getVnpayTxnRef()).isEqualTo("ORD-MULTI-305");
        assertThat(order.getVnpayBankCode()).isEqualTo("VCB");
        assertThat(order.getVnpayPayDate()).isEqualTo("20261024100400");
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
        verify(orderVoucherReleasePort, never()).releaseOrderVoucherIfPresent(anyLong());
    }

    @Test
    @DisplayName("P1 Regression: 7 payment attempts persisted losslessly without dropping middle attempt, reconciles PAID without stock loss")
    void testReconcile_sevenAttemptsPersistedLosslessly_middleAttemptSucceeds_reconcilesPaidWithoutStockLoss() throws Exception {
        User customer = User.builder().id(88L).fullName("Test P1 Customer").build();
        Order order = Order.builder()
                .id(88L)
                .user(customer)
                .orderCode("ORD-7ATTEMPTS")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("300000.00"))
                .stockDeductedAt(LocalDateTime.of(2026, 10, 16, 10, 0, 0))
                .build();

        // 7 attempts across 7 different timestamps
        order.recordVnpayPaymentAttempt("ORD-7ATTEMPTS", "20261016100100");
        order.recordVnpayPaymentAttempt("ORD-7ATTEMPTS-R100200", "20261016100200");
        order.recordVnpayPaymentAttempt("ORD-7ATTEMPTS-R100300", "20261016100300");
        order.recordVnpayPaymentAttempt("ORD-7ATTEMPTS-R100400", "20261016100400");
        order.recordVnpayPaymentAttempt("ORD-7ATTEMPTS-R100500", "20261016100500");
        order.recordVnpayPaymentAttempt("ORD-7ATTEMPTS-R100600", "20261016100600");
        order.recordVnpayPaymentAttempt("ORD-7ATTEMPTS-R100700", "20261016100700");

        assertThat(order.getVnpayPaymentAttempts()).hasSize(7);
        String persistedTxnRef = order.getVnpayTxnRef();
        String persistedPayDate = order.getPersistedVnpayBankCodeColumn() != null ? "INIT:20261016100700" : null;
        String persistedBankCode = order.getPersistedVnpayBankCodeColumn();

        // Ensure encoded column fits within 50 chars and did NOT drop anything
        assertThat(persistedBankCode).isNotNull();
        assertThat(persistedBankCode.length()).isLessThanOrEqualTo(50);

        // Simulate reloading fresh entity strictly from persisted columns (as if loaded from MySQL)
        Order reloadedOrder = Order.builder()
                .id(88L)
                .user(customer)
                .orderCode("ORD-7ATTEMPTS")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("300000.00"))
                .stockDeductedAt(LocalDateTime.of(2026, 10, 16, 10, 0, 0))
                .vnpayTxnRef(persistedTxnRef)
                .vnpayPayDate(persistedPayDate)
                .vnpayBankCode(persistedBankCode)
                .build();
        reloadedOrder.setCreatedAt(LocalDateTime.of(2026, 10, 16, 10, 0, 0));

        // In JPA, @PostLoad triggers restoreVnpayInitiationMetadata()
        java.lang.reflect.Method postLoadMethod = Order.class.getDeclaredMethod("restoreVnpayInitiationMetadata");
        postLoadMethod.setAccessible(true);
        postLoadMethod.invoke(reloadedOrder);

        // Verify that ALL 7 attempts are preserved in the reloaded entity!
        List<Order.VnpayPaymentAttempt> reloadedAttempts = reloadedOrder.getVnpayPaymentAttempts();
        assertThat(reloadedAttempts).hasSize(7);
        assertThat(reloadedAttempts).extracting(Order.VnpayPaymentAttempt::txnRef)
                .containsExactly(
                        "ORD-7ATTEMPTS",
                        "ORD-7ATTEMPTS-R100200",
                        "ORD-7ATTEMPTS-R100300",
                        "ORD-7ATTEMPTS-R100400",
                        "ORD-7ATTEMPTS-R100500",
                        "ORD-7ATTEMPTS-R100600",
                        "ORD-7ATTEMPTS-R100700"
                );

        // Configure stub server: Attempt #2 ("ORD-7ATTEMPTS-R100200") was paid, other 6 return NOT_FOUND (91)
        String apiUrl = startQueryDrStubServer(200, requestJson -> {
            JsonNode reqNode = objectMapper.readTree(requestJson);
            String queriedRef = reqNode.path("vnp_TxnRef").asText();
            String queriedDate = reqNode.path("vnp_TransactionDate").asText();
            if ("ORD-7ATTEMPTS-R100200".equals(queriedRef) && "20261016100200".equals(queriedDate)) {
                return buildIndependentSpecSignedQueryDrJson(
                        "RSP-ATTEMPT-PAID", "querydr", "00", "OK",
                        "AURELIA01", "ORD-7ATTEMPTS-R100200", "30000000", "NCB", "20261016100205",
                        "14567891", "01", "00", "QueryDR ORD-7ATTEMPTS", "", "", TEST_SECRET
                );
            }
            return buildIndependentSpecSignedQueryDrJson(
                    "RSP-ATTEMPT-91", "querydr", "91", "Khong tim thay giao dich yeu cau",
                    "AURELIA01", queriedRef, "", "", "", "", "", "",
                    "QueryDR ORD-7ATTEMPTS", "", "", TEST_SECRET
            );
        });

        VNPayConfig config = configuredForQueryDr(apiUrl);
        DefaultVNPayGatewayReconciliationClient queryDrClient =
                new DefaultVNPayGatewayReconciliationClient(config, objectMapper);
        VnpayTimeoutCleanerDaemon daemon = new VnpayTimeoutCleanerDaemon(
                orderRepository, config, queryDrClient, stockMutationService,
                orderVoucherReleasePort, auditLogService, objectMapper
        );

        when(orderRepository.findExpiredPendingPaymentOrders(any())).thenReturn(List.of(reloadedOrder));
        when(orderRepository.findByIdWithLock(88L)).thenReturn(Optional.of(reloadedOrder));

        int cancelled = daemon.cleanExpiredPendingPaymentOrders();

        // Order must transition to PENDING_CONFIRMATION and PAID
        assertThat(cancelled).isZero();
        assertThat(reloadedOrder.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        assertThat(reloadedOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(reloadedOrder.getVnpayTxnRef()).isEqualTo("ORD-7ATTEMPTS-R100200");
        assertThat(reloadedOrder.getVnpayBankCode()).isEqualTo("NCB");

        // Stock must NEVER be restored and voucher must NEVER be cancelled
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
        verify(orderVoucherReleasePort, never()).releaseOrderVoucherIfPresent(anyLong());
    }

    @Test
    @DisplayName("P1 Regression: canRecordVnpayPaymentAttempt blocks recording when 50-char column limit would be exceeded")
    void testRecordVnpayPaymentAttempt_blocksBeforeOverwritingOrTruncating() {
        Order order = Order.builder()
                .id(89L)
                .orderCode("ORD-VERY-LONG-BASE-CODE-12345678")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .build();

        // Record several custom-format attempts until column capacity is reached
        int recorded = 0;
        for (int i = 1; i <= 30; i++) {
            String time = String.format("2026101610%02d00", i % 60);
            String ref = order.getOrderCode() + "-CUSTOM-TAG-" + i;
            if (order.canRecordVnpayPaymentAttempt(ref, time)) {
                order.recordVnpayPaymentAttempt(ref, time);
                recorded++;
            } else {
                assertThatThrownBy(() -> order.recordVnpayPaymentAttempt(ref, time))
                        .isInstanceOf(com.ddagtech.aureliabooks.exception.AppException.class)
                        .hasMessageContaining("Maximum VNPay payment retry attempts reached");
                break;
            }
        }
        assertThat(recorded).isGreaterThan(0);
        // Ensure that attempts recorded so far were not lost
        assertThat(order.getVnpayPaymentAttempts().size()).isEqualTo(recorded);
    }

    private VNPayConfig configuredForQueryDr(String apiUrl) {
        VNPayConfig config = new VNPayConfig();
        config.setTmnCode("AURELIA01");
        config.setHashSecret(TEST_SECRET);
        config.setApiUrl(apiUrl);
        config.setVersion("2.1.0");
        config.setExpireMinutes(15);
        return config;
    }

    @FunctionalInterface
    private interface StubResponseHandler {
        String handle(String requestBody) throws Exception;
    }

    private String startQueryDrStubServer(int statusCode, StubResponseHandler handler) throws IOException {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/merchant_webapi/api/transaction", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String responseBody;
            try {
                responseBody = handler.handle(requestBody);
            } catch (Exception ex) {
                responseBody = "{}";
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        stubServer.start();
        int port = stubServer.getAddress().getPort();
        return "http://127.0.0.1:" + port + "/merchant_webapi/api/transaction";
    }

    /**
     * Independent fixture signing QueryDR response strictly according to VNPay's 15-field specification:
     * vnp_ResponseId | vnp_Command | vnp_ResponseCode | vnp_Message | vnp_TmnCode | vnp_TxnRef
     * | vnp_Amount | vnp_BankCode | vnp_PayDate | vnp_TransactionNo | vnp_TransactionType
     * | vnp_TransactionStatus | vnp_OrderInfo | vnp_PromotionCode | vnp_PromotionAmount
     */
    private String buildIndependentSpecSignedQueryDrJson(
            String responseId,
            String command,
            String responseCode,
            String message,
            String tmnCode,
            String txnRef,
            String amountMinor,
            String bankCode,
            String payDate,
            String transactionNo,
            String transactionType,
            String transactionStatus,
            String orderInfo,
            String promotionCode,
            String promotionAmount,
            String secret
    ) throws Exception {
        String spec15FieldData = responseId + "|"
                + command + "|"
                + responseCode + "|"
                + message + "|"
                + tmnCode + "|"
                + txnRef + "|"
                + amountMinor + "|"
                + bankCode + "|"
                + payDate + "|"
                + transactionNo + "|"
                + transactionType + "|"
                + transactionStatus + "|"
                + orderInfo + "|"
                + promotionCode + "|"
                + promotionAmount;
        String secureHash = VNPayUtil.hmacSHA512(secret, spec15FieldData);

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("vnp_ResponseId", responseId);
        payload.put("vnp_Command", command);
        payload.put("vnp_ResponseCode", responseCode);
        payload.put("vnp_Message", message);
        payload.put("vnp_TmnCode", tmnCode);
        payload.put("vnp_TxnRef", txnRef);
        payload.put("vnp_Amount", amountMinor);
        payload.put("vnp_BankCode", bankCode);
        payload.put("vnp_PayDate", payDate);
        payload.put("vnp_TransactionNo", transactionNo);
        payload.put("vnp_TransactionType", transactionType);
        payload.put("vnp_TransactionStatus", transactionStatus);
        payload.put("vnp_OrderInfo", orderInfo);
        payload.put("vnp_PromotionCode", promotionCode);
        payload.put("vnp_PromotionAmount", promotionAmount);
        payload.put("vnp_SecureHash", secureHash);
        return objectMapper.writeValueAsString(payload);
    }
}
