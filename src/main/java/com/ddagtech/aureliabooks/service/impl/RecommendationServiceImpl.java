package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.mapper.ProductMapper;
import com.ddagtech.aureliabooks.repository.OrderItemRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.InMemoryProductVectorStore;
import com.ddagtech.aureliabooks.service.RecommendationService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Uses cached embeddings when available and bounded catalogue fallbacks otherwise. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationServiceImpl implements RecommendationService {

    private static final int LIMIT = 4;
    private final ProductRepository products;
    private final OrderItemRepository orderItems;
    private final InMemoryProductVectorStore vectors;

    @Override
    public List<ProductSummary> similar(Long productId) {
        Product current = products.findById(productId).orElse(null);
        if (current == null || !Boolean.TRUE.equals(current.getIsActive())) return List.of();
        double[] query = vectors.getByProductId(productId).map(InMemoryProductVectorStore.ProductVectorEntry::vector)
                .orElseGet(() -> new double[0]);
        return select(query, Set.of(productId), current.getCategory() == null ? null : current.getCategory().getId());
    }

    @Override
    public List<ProductSummary> personalized(Long customerId, Long currentProductId) {
        if (customerId == null) return List.of();
        Set<Long> purchased = new HashSet<>(orderItems.findDeliveredProductIds(customerId));
        double[] centroid = vectors.computeCentroid(purchased);
        if (currentProductId != null) purchased.add(currentProductId);
        return select(centroid, purchased, null);
    }

    private List<ProductSummary> select(double[] query, Set<Long> excluded, Long categoryId) {
        LinkedHashMap<Long, Product> selected = new LinkedHashMap<>();
        List<Long> ranked = vectors.findTopSimilar(query, excluded, 32).stream()
                .map(InMemoryProductVectorStore.ProductVectorEntry::productId).toList();
        if (!ranked.isEmpty()) {
            var eligible = products.findRecommendationCandidates(ranked);
            var byId = new java.util.HashMap<Long, Product>();
            eligible.forEach(product -> byId.put(product.getId(), product));
            ranked.stream().filter(byId::containsKey).forEach(id -> selected.put(id, byId.get(id)));
        }
        // Recheck availability in SQL; cache entries may outlive stock or active changes.
        var fallback = new ArrayList<>(products.findRecommendationFallback(categoryId, PageRequest.of(0, 32)));
        if (categoryId != null && fallback.size() < LIMIT) {
            fallback.addAll(products.findRecommendationFallback(null, PageRequest.of(0, 32)));
        }
        fallback.forEach(product -> selected.putIfAbsent(product.getId(), product));
        return selected.values().stream().filter(product -> !excluded.contains(product.getId()))
                .limit(LIMIT).map(product -> ProductMapper.toSummary(product, null)).toList();
    }
}
