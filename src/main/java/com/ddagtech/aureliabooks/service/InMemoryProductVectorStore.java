package com.ddagtech.aureliabooks.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe in-memory semantic vector store running in the JVM heap (FE-6 / CRON-02).
 * Stores product embedding vectors loaded from products.embedding JSON column and computes
 * cosine similarity recommendations while dynamically excluding already-purchased products (BR-05-03).
 */
@Component
public class InMemoryProductVectorStore {

    private final ConcurrentHashMap<Long, ProductVectorEntry> vectorIndex = new ConcurrentHashMap<>();
    private final AtomicReference<LocalDateTime> lastRefreshedAt = new AtomicReference<>();

    /**
     * Immutable entry representing a product's normalized embedding vector in heap memory.
     */
    public record ProductVectorEntry(
            Long productId,
            String title,
            double[] vector
    ) {
    }

    /**
     * Replaces the in-memory vector index atomically after a preloader refresh cycle.
     *
     * @param freshEntries map of productId to vector entries
     */
    public void replaceAll(Map<Long, ProductVectorEntry> freshEntries) {
        vectorIndex.clear();
        if (freshEntries != null && !freshEntries.isEmpty()) {
            vectorIndex.putAll(freshEntries);
        }
        lastRefreshedAt.set(LocalDateTime.now());
    }

    /**
     * Inserts or updates a single product embedding in the in-memory store.
     */
    public void upsert(Long productId, String title, double[] vector) {
        if (productId == null || vector == null || vector.length == 0) {
            return;
        }
        vectorIndex.put(productId, new ProductVectorEntry(productId, title, vector.clone()));
        lastRefreshedAt.set(LocalDateTime.now());
    }

    /**
     * Returns the number of active product vectors currently cached in heap memory.
     */
    public int size() {
        return vectorIndex.size();
    }

    /**
     * Returns the timestamp of the most recent vector cache refresh.
     */
    public LocalDateTime getLastRefreshedAt() {
        return lastRefreshedAt.get();
    }

    /**
     * Computes top-K most semantically similar products by cosine similarity,
     * dynamically excluding the source product and any already-purchased product IDs.
     *
     * @param queryVector        reference embedding vector (e.g., current product or user centroid)
     * @param excludedProductIds product IDs to exclude (e.g., already purchased by customer)
     * @param topK               maximum number of recommendations to return
     * @return ordered list of recommended product entries
     */
    public List<ProductVectorEntry> findTopSimilar(double[] queryVector, Set<Long> excludedProductIds, int topK) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        Set<Long> exclusions = excludedProductIds != null ? excludedProductIds : Set.of();

        return vectorIndex.values().stream()
                .filter(entry -> !exclusions.contains(entry.productId()))
                .filter(entry -> entry.vector().length == queryVector.length)
                .sorted(Comparator.comparingDouble(
                        (ProductVectorEntry entry) -> cosineSimilarity(queryVector, entry.vector())
                ).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * Computes the cosine similarity between two equal-length vectors.
     */
    public static double cosineSimilarity(double[] vectorA, double[] vectorB) {
        if (vectorA == null || vectorB == null || vectorA.length == 0 || vectorA.length != vectorB.length) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0.0 ? 0.0 : (dotProduct / denominator);
    }
}
