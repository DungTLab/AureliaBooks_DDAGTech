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
import com.ddagtech.aureliabooks.util.VNPayUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
 * (V2.4 §5.2 / BR-04-04 / F01 / F02).
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
    void stopStubServer() {
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

        // Candidate loaded into L1 cache before concurrent IPN cancel committed
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

        // Simulate EntityManager.refresh(..., PESSIMISTIC_WRITE) reloading the committed IPN cancel state from DB
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

        // Simulate EntityManager.refresh(..., PESSIMISTIC_WRITE) reloading the committed IPN PAID state from DB
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
    // F01: DefaultVNPayGatewayReconciliationClient HTTP QueryDR Tests
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
    @DisplayName("F01: DefaultVNPayGatewayReconciliationClient should return VERIFIED_PAID when QueryDR HTTP endpoint returns signed 00/00")
    void defaultClient_QueryDrReturnsSignedPaid_ReturnsVerifiedPaid() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String apiUrl = startQueryDrStubServer(200, requestJson -> {
            capturedBody.set(requestJson);
            return buildSignedQueryDrResponse(requestJson, "00", "00", "25000000", "VCB", "20261024100500", TEST_SECRET);
        });

        VNPayConfig config = configuredForQueryDr(apiUrl);
        DefaultVNPayGatewayReconciliationClient client =
                new DefaultVNPayGatewayReconciliationClient(config, objectMapper);

        Order expiredOrder = Order.builder()
                .id(202L)
                .orderCode("ORD-QDR-202")
                .vnpayTxnRef("ORD-QDR-202")
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
    @DisplayName("F01: DefaultVNPayGatewayReconciliationClient should return VERIFIED_UNPAID_OR_FAILED when QueryDR returns signed 00/02")
    void defaultClient_QueryDrReturnsSignedTerminalFailure_ReturnsVerifiedUnpaidOrFailed() throws Exception {
        String apiUrl = startQueryDrStubServer(200, requestJson ->
                buildSignedQueryDrResponse(requestJson, "00", "02", "18000000", "NCB", "20261024100400", TEST_SECRET)
        );

        VNPayConfig config = configuredForQueryDr(apiUrl);
        DefaultVNPayGatewayReconciliationClient client =
                new DefaultVNPayGatewayReconciliationClient(config, objectMapper);

        Order expiredOrder = Order.builder()
                .id(203L)
                .orderCode("ORD-QDR-203")
                .vnpayTxnRef("ORD-QDR-203")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .build();
        expiredOrder.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0, 0));

        ReconciliationResult result = client.reconcileExpiredOrder(expiredOrder);

        assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.VERIFIED_UNPAID_OR_FAILED);
    }

    @Test
    @DisplayName("F01: DefaultVNPayGatewayReconciliationClient must return INDETERMINATE on incomplete status (01), transaction not found (91), forged checksum, or HTTP 500")
    void defaultClient_InconclusiveOrTamperedQueryDrResponses_ReturnIndeterminate() throws Exception {
        Order expiredOrder = Order.builder()
                .id(204L)
                .orderCode("ORD-QDR-204")
                .vnpayTxnRef("ORD-QDR-204")
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("180000.00"))
                .build();
        expiredOrder.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0, 0));

        // 1. Incomplete transaction status (00 / 01)
        String apiUrlPending = startQueryDrStubServer(200, requestJson ->
                buildSignedQueryDrResponse(requestJson, "00", "01", "18000000", "NCB", "20261024100400", TEST_SECRET)
        );
        DefaultVNPayGatewayReconciliationClient pendingClient =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrlPending), objectMapper);
        assertThat(pendingClient.reconcileExpiredOrder(expiredOrder).outcome())
                .isEqualTo(ReconciliationOutcome.INDETERMINATE);
        stopStubServer();

        // 2. Transaction not found at gateway (91)
        String apiUrlNotFound = startQueryDrStubServer(200, requestJson ->
                buildSignedQueryDrResponse(requestJson, "91", "", "18000000", "", "", TEST_SECRET)
        );
        DefaultVNPayGatewayReconciliationClient notFoundClient =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrlNotFound), objectMapper);
        assertThat(notFoundClient.reconcileExpiredOrder(expiredOrder).outcome())
                .isEqualTo(ReconciliationOutcome.INDETERMINATE);
        stopStubServer();

        // 3. Invalid response checksum (signed with wrong key)
        String apiUrlForged = startQueryDrStubServer(200, requestJson ->
                buildSignedQueryDrResponse(requestJson, "00", "02", "18000000", "NCB", "20261024100400", "WRONG_KEY")
        );
        DefaultVNPayGatewayReconciliationClient forgedClient =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrlForged), objectMapper);
        assertThat(forgedClient.reconcileExpiredOrder(expiredOrder).outcome())
                .isEqualTo(ReconciliationOutcome.INDETERMINATE);
        stopStubServer();

        // 4. HTTP 500 gateway error
        String apiUrl500 = startQueryDrStubServer(500, requestJson -> "{\"error\":\"gateway down\"}");
        DefaultVNPayGatewayReconciliationClient error500Client =
                new DefaultVNPayGatewayReconciliationClient(configuredForQueryDr(apiUrl500), objectMapper);
        assertThat(error500Client.reconcileExpiredOrder(expiredOrder).outcome())
                .isEqualTo(ReconciliationOutcome.INDETERMINATE);
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

    private String buildSignedQueryDrResponse(
            String requestJson,
            String responseCode,
            String transactionStatus,
            String amountMinor,
            String bankCode,
            String payDate,
            String secret
    ) throws Exception {
        JsonNode req = objectMapper.readTree(requestJson);
        String responseId = "RSP-" + req.path("vnp_RequestId").asText();
        String command = req.path("vnp_Command").asText("querydr");
        String tmnCode = req.path("vnp_TmnCode").asText("AURELIA01");
        String txnRef = req.path("vnp_TxnRef").asText();
        String transactionNo = "14599901";
        String transactionType = "01";
        String message = "QueryDR completed";

        String dataToSign = String.join("|",
                responseId,
                command,
                responseCode,
                message,
                tmnCode,
                txnRef,
                amountMinor,
                bankCode,
                payDate,
                transactionNo,
                transactionType,
                transactionStatus,
                "",
                ""
        );
        String secureHash = VNPayUtil.hmacSHA512(secret, dataToSign);

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
        payload.put("vnp_PromotionCode", "");
        payload.put("vnp_PromotionAmount", "");
        payload.put("vnp_SecureHash", secureHash);
        return objectMapper.writeValueAsString(payload);
    }
}
