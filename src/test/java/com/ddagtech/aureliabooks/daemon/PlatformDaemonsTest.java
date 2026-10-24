package com.ddagtech.aureliabooks.daemon;

import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.InMemoryProductVectorStore;
import com.ddagtech.aureliabooks.service.InMemoryProductVectorStore.ProductVectorEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionCreatedEvent;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit and filter-integration test suite for Platform Background Daemons:
 * - CRON-02: {@link RecommendationPreloaderDaemon} & {@link InMemoryProductVectorStore} (JVM heap embeddings)
 * - CRON-03: {@link SessionRegistryCleanupDaemon} (expired session purging without bypassing forced logout)
 */
@ExtendWith(MockitoExtension.class)
class PlatformDaemonsTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private SessionRegistry sessionRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        assertThat(vectorStore.getByProductId(1L)).isPresent();

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
    @DisplayName("CRON-02: computeCentroid() should calculate mean User Centroid Vector and exclude purchased products in personalized search")
    void cron02_ComputeCentroid_CalculatesMeanVectorAndExcludesPurchasedIds() {
        InMemoryProductVectorStore vectorStore = new InMemoryProductVectorStore();
        vectorStore.upsert(1L, "Clean Code", new double[]{1.0, 0.0, 0.0});
        vectorStore.upsert(2L, "Refactoring", new double[]{0.8, 0.2, 0.0});
        vectorStore.upsert(3L, "Effective Java", new double[]{0.85, 0.15, 0.0});

        Set<Long> purchasedIds = Set.of(1L, 2L);
        double[] centroid = vectorStore.computeCentroid(purchasedIds);

        assertThat(centroid).hasSize(3);
        assertThat(centroid[0]).isCloseTo(0.9, within(1e-6));
        assertThat(centroid[1]).isCloseTo(0.1, within(1e-6));
        assertThat(centroid[2]).isCloseTo(0.0, within(1e-6));

        List<ProductVectorEntry> personalized = vectorStore.findTopSimilar(centroid, purchasedIds, 5);
        assertThat(personalized).hasSize(1);
        assertThat(personalized.get(0).productId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("CRON-03: purgeExpiredSessions() should remove stale expired sessions while preserving fresh untracked expired markers")
    void cron03_PurgeExpiredSessions_RemovesStaleExpiredAndPreservesFreshUntrackedExpiredMarker() {
        SessionRegistryCleanupDaemon daemon = new SessionRegistryCleanupDaemon(sessionRegistry);

        long now = System.currentTimeMillis();
        Object principal = "staff@aureliabook.vn";
        SessionInformation activeSession = new SessionInformation(principal, "sess-active-1", new Date(now));
        SessionInformation freshExpiredSession = new SessionInformation(principal, "sess-fresh-expired-2", new Date(now));
        freshExpiredSession.expireNow();
        SessionInformation staleExpiredSession = new SessionInformation(
                principal,
                "sess-stale-expired-3",
                new Date(now - SessionRegistryCleanupDaemon.DEFAULT_EXPIRED_SESSION_RETENTION_MS - 5_000L)
        );
        staleExpiredSession.expireNow();

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(principal));
        when(sessionRegistry.getAllSessions(principal, true))
                .thenReturn(List.of(activeSession, freshExpiredSession, staleExpiredSession));

        int purged = daemon.purgeExpiredSessions();

        assertThat(purged).isEqualTo(1);
        verify(sessionRegistry).removeSessionInformation("sess-stale-expired-3");
        verify(sessionRegistry, never()).removeSessionInformation("sess-fresh-expired-2");
        verify(sessionRegistry, never()).removeSessionInformation("sess-active-1");
    }

    @Test
    @DisplayName("CRON-03 (P1 Security Regression): running purgeExpiredSessions() after expireNow() must NOT allow ConcurrentSessionFilter to let the next request pass")
    void cron03_ConcurrentSessionFilter_CleanupBeforeNextRequestStillEnforcesForcedLogout() throws Exception {
        SessionRegistry realRegistry = new SessionRegistryImpl();
        SessionRegistryCleanupDaemon daemon = new SessionRegistryCleanupDaemon(realRegistry);
        ConcurrentSessionFilter concurrentSessionFilter = new ConcurrentSessionFilter(realRegistry);

        MockHttpSession httpSession = new MockHttpSession();
        String sessionId = httpSession.getId();
        Object principal = "locked.staff@aureliabook.vn";

        realRegistry.registerNewSession(sessionId, principal);
        SessionInformation sessionInfo = realRegistry.getSessionInformation(sessionId);
        assertThat(sessionInfo).isNotNull();

        // Admin locks user or changes role -> marks session expired via expireNow()
        sessionInfo.expireNow();

        // Daemon runs BEFORE the user's next HTTP request
        daemon.purgeExpiredSessions();

        // User sends their next HTTP request carrying the same session
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/staff/orders");
        request.setSession(httpSession);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean downstreamReached = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> downstreamReached.set(true);

        concurrentSessionFilter.doFilter(request, response, filterChain);

        // Request MUST be blocked by ConcurrentSessionFilter and the HttpSession MUST be invalidated
        assertThat(downstreamReached.get()).isFalse();
        assertThat(httpSession.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("CRON-03: when HttpSession is tracked via HttpSessionCreatedEvent, purgeExpiredSessions() invalidates the HttpSession before removing registry entry")
    void cron03_TrackedHttpSession_InvalidatesSessionBeforeRemovingRegistryEntry() {
        SessionRegistry realRegistry = new SessionRegistryImpl();
        SessionRegistryCleanupDaemon daemon = new SessionRegistryCleanupDaemon(realRegistry);

        MockHttpSession httpSession = new MockHttpSession();
        String sessionId = httpSession.getId();
        Object principal = "re-roled.staff@aureliabook.vn";

        daemon.onHttpSessionCreated(new HttpSessionCreatedEvent(httpSession));
        realRegistry.registerNewSession(sessionId, principal);
        realRegistry.getSessionInformation(sessionId).expireNow();

        int purged = daemon.purgeExpiredSessions();

        assertThat(purged).isEqualTo(1);
        assertThat(httpSession.isInvalid()).isTrue();
        assertThat(realRegistry.getSessionInformation(sessionId)).isNull();
    }

    @Test
    @DisplayName("CRON-03 (F01 Regression): purgeExpiredSessions() must purge destroyed guest session IDs even when SessionRegistry is empty")
    void cron03_PurgeExpiredSessions_CleansDestroyedSessionIdsEvenWhenRegistryIsEmpty() {
        SessionRegistry realRegistry = new SessionRegistryImpl();
        SessionRegistryCleanupDaemon daemon = new SessionRegistryCleanupDaemon(realRegistry);

        // Simulate 30 guest CSRF sessions created and then destroyed by servlet container
        for (int i = 0; i < 30; i++) {
            daemon.markHttpSessionDestroyed("guest-csrf-session-" + i);
        }
        assertThat(daemon.getDestroyedSessionCount()).isEqualTo(30);

        // Running purgeExpiredSessions() must reclaim all 30 guest session IDs because registry has 0 principals
        int purged = daemon.purgeExpiredSessions();

        assertThat(purged).isEqualTo(0);
        assertThat(daemon.getDestroyedSessionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("CRON-03 (F01 Regression): purgeExpiredSessions() prunes unneeded guest IDs while safely purging authenticated expired sessions")
    void cron03_PurgeExpiredSessions_PrunesGuestSessionIdsWhilePreservingLegitimateExpiredTrackedSessions() {
        SessionRegistry realRegistry = new SessionRegistryImpl();
        SessionRegistryCleanupDaemon daemon = new SessionRegistryCleanupDaemon(realRegistry);

        // Register 1 active user session and 1 expired user session
        String activeUserSessionId = "user-sess-active";
        String expiredUserSessionId = "user-sess-expired";
        realRegistry.registerNewSession(activeUserSessionId, "active.user@aureliabook.vn");
        realRegistry.registerNewSession(expiredUserSessionId, "expired.user@aureliabook.vn");
        realRegistry.getSessionInformation(expiredUserSessionId).expireNow();

        // Simulate container destroying 5 guest sessions and reporting the expired user session destroyed
        for (int i = 0; i < 5; i++) {
            daemon.markHttpSessionDestroyed("guest-" + i);
        }
        daemon.markHttpSessionDestroyed(expiredUserSessionId);

        assertThat(daemon.getDestroyedSessionCount()).isEqualTo(6);

        // Run cleanup daemon pass
        int purged = daemon.purgeExpiredSessions();

        // The expired user session should be safely purged from registry and destroyedSessionIds
        assertThat(purged).isEqualTo(1);
        assertThat(realRegistry.getSessionInformation(expiredUserSessionId)).isNull();
        // The active user session remains untouched
        assertThat(realRegistry.getSessionInformation(activeUserSessionId)).isNotNull();
        // All guest IDs and the purged expired ID should be completely evicted
        assertThat(daemon.getDestroyedSessionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("CRON-03: purgeExpiredSessions() prunes invalidated dead HttpSessions from internal activeHttpSessions map")
    void cron03_PurgeExpiredSessions_PrunesInvalidatedHttpSessionsFromActiveMap() {
        SessionRegistry realRegistry = new SessionRegistryImpl();
        SessionRegistryCleanupDaemon daemon = new SessionRegistryCleanupDaemon(realRegistry);

        MockHttpSession sessionValid = new MockHttpSession();
        MockHttpSession sessionInvalidated = new MockHttpSession();

        daemon.registerHttpSession(sessionValid);
        daemon.registerHttpSession(sessionInvalidated);
        assertThat(daemon.getActiveHttpSessionCount()).isEqualTo(2);

        // Invalidate one session outside daemon
        sessionInvalidated.invalidate();

        daemon.purgeExpiredSessions();

        // Only the valid session remains tracked in activeHttpSessions
        assertThat(daemon.getActiveHttpSessionCount()).isEqualTo(1);
    }
}
