package com.ddagtech.aureliabooks.daemon;

import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.InMemoryProductVectorStore;
import com.ddagtech.aureliabooks.service.InMemoryProductVectorStore.ProductVectorEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CRON-02: Recommendation Vector Preloader Daemon (FE-6).
 *
 * Warms up and refreshes the in-memory JVM vector store from products.embedding JSON column
 * on application startup and on a scheduled nightly cron cycle (02:00 AM).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationPreloaderDaemon {

    private static final TypeReference<List<Double>> DOUBLE_LIST_TYPE = new TypeReference<>() {};

    private final ProductRepository productRepository;
    private final InMemoryProductVectorStore vectorStore;
    private final ObjectMapper objectMapper;

    /**
     * Warms up the in-memory vector store when the Spring Boot application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warmUpOnStartup() {
        preloadProductVectors();
    }

    /**
     * Periodically reloads active product embeddings into JVM heap memory (default: 02:00 AM daily).
     *
     * @return number of valid product embedding vectors loaded into memory
     */
    @Scheduled(cron = "${app.cron.vector-preload:0 0 2 * * ?}")
    @Transactional(readOnly = true)
    public int preloadProductVectors() {
        List<Product> activeProducts = productRepository.findActiveProductsWithEmbedding();
        Map<Long, ProductVectorEntry> loadedEntries = new HashMap<>();

        for (Product product : activeProducts) {
            if (product.getId() == null || product.getEmbedding() == null || product.getEmbedding().isBlank()) {
                continue;
            }
            double[] parsedVector = parseEmbeddingJson(product.getEmbedding(), product.getId());
            if (parsedVector.length > 0) {
                loadedEntries.put(
                        product.getId(),
                        new ProductVectorEntry(product.getId(), product.getTitle(), parsedVector)
                );
            }
        }

        vectorStore.replaceAll(loadedEntries);
        log.info("CRON-02: Preloaded {} active product embedding vectors into JVM InMemoryProductVectorStore.",
                loadedEntries.size());
        return loadedEntries.size();
    }

    private double[] parseEmbeddingJson(String rawJson, Long productId) {
        try {
            List<Double> values = objectMapper.readValue(rawJson, DOUBLE_LIST_TYPE);
            if (values == null || values.isEmpty()) {
                return new double[0];
            }
            double[] vector = new double[values.size()];
            for (int i = 0; i < values.size(); i++) {
                Double val = values.get(i);
                vector[i] = val != null ? val : 0.0;
            }
            return vector;
        } catch (Exception ex) {
            log.warn("CRON-02: Skipping malformed embedding JSON for product id={}: {}", productId, ex.getMessage());
            return new double[0];
        }
    }
}
