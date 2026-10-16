package com.ddagtech.aureliabooks.daemon;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.service.InMemoryProductVectorStore;
import com.ddagtech.aureliabooks.service.InMemoryProductVectorStore.ProductVectorEntry;
import com.ddagtech.aureliabooks.service.OrderVoucherReleasePort;
import com.ddagtech.aureliabooks.service.StockMutationService;
import com.ddagtech.aureliabooks.service.VNPayGatewayReconciliationClient;
import com.ddagtech.aureliabooks.service.VNPayGatewayReconciliationClient.ReconciliationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for Platform Background Daemons:
 * - CRON-01: {@link VnpayTimeoutCleanerDaemon} (15-minute TTL reconciliation & one-time stock restore)
 * - CRON-02: {@link RecommendationPreloaderDaemon} & {@link InMemoryProductVectorStore} (JVM heap embeddings)
 * - CRON-03: {@link SessionRegistryCleanupDaemon} (expired session purging)
 */
@ExtendWith(MockitoExtension.class)
class PlatformDaemonsTest {

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

    @Mock
    private ProductRepository productRepository;

    @Mock
    private SessionRegistry sessionRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                .thenReturn(ReconciliationResult.unpaidOrFailed("Verified unpaid at gateway"));

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
    @DisplayName("CRON-02: preloadProductVectors() should load active embeddings into JVM store and dynamically exclude purchased products")
    void cron02_PreloadProductVectors_LoadsEmbeddingsAndComputesCosineSimilarityWithExclusions() {
        InMemoryProductVectorStore vectorStore = new InMemoryProductVectorStore();
        RecommendationPreloaderDaemon daemon = new RecommendationPreloaderDaemon(
                productRepository,
                vectorStore,
                objectMapper
        );

        Product p1 = Product.builder().id(1L).title("Clean Architecture").embedding("[1.0, 0.0, 0.0]").isActive(true).build();
        Product p2 = Product.builder().id(2L).title("Domain-Driven Design").embedding("[0.9, 0.1, 0.0]").isActive(true).build();
        Product p3 = Product.builder().id(3L).title("Spring Boot in Action").embedding("[0.8, 0.2, 0.0]").isActive(true).build();
        Product pMalformed = Product.builder().id(4L).title("Broken JSON").embedding("not-a-json-array").isActive(true).build();

        when(productRepository.findActiveProductsWithEmbedding()).thenReturn(List.of(p1, p2, p3, pMalformed));

        int loadedCount = daemon.preloadProductVectors();

        assertThat(loadedCount).isEqualTo(3);
        assertThat(vectorStore.size()).isEqualTo(3);
        assertThat(vectorStore.getLastRefreshedAt()).isNotNull();

        // Query recommendations for [1.0, 0.0, 0.0], excluding product 1 (self) and product 2 (already purchased per BR-05-03)
        List<ProductVectorEntry> recommendations = vectorStore.findTopSimilar(
                new double[]{1.0, 0.0, 0.0},
                Set.of(1L, 2L),
                5
        );
        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).productId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("CRON-03: purgeExpiredSessions() should remove only expired SessionInformation entries from SessionRegistry")
    void cron03_PurgeExpiredSessions_RemovesExpiredOnly() {
        SessionRegistryCleanupDaemon daemon = new SessionRegistryCleanupDaemon(sessionRegistry);

        Object principal = "staff@aureliabook.vn";
        SessionInformation activeSession = new SessionInformation(principal, "sess-active-1", new Date());
        SessionInformation expiredSession = new SessionInformation(principal, "sess-expired-2", new Date());
        expiredSession.expireNow();

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(principal));
        when(sessionRegistry.getAllSessions(principal, true)).thenReturn(List.of(activeSession, expiredSession));

        int purged = daemon.purgeExpiredSessions();

        assertThat(purged).isEqualTo(1);
        verify(sessionRegistry).removeSessionInformation("sess-expired-2");
        verify(sessionRegistry, never()).removeSessionInformation("sess-active-1");
    }
}
