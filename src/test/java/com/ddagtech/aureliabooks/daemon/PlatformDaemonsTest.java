package com.ddagtech.aureliabooks.daemon;

import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.InMemoryProductVectorStore;
import com.ddagtech.aureliabooks.service.InMemoryProductVectorStore.ProductVectorEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for Platform Background Daemons:
 * - CRON-02: {@link RecommendationPreloaderDaemon} & {@link InMemoryProductVectorStore} (JVM heap embeddings)
 * - CRON-03: {@link SessionRegistryCleanupDaemon} (expired session purging)
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
