package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.OrderItemRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.impl.RecommendationServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {
    @Mock ProductRepository products;
    @Mock OrderItemRepository orders;
    private InMemoryProductVectorStore vectors;
    private RecommendationServiceImpl service;

    @BeforeEach
    void setup() {
        vectors = new InMemoryProductVectorStore();
        service = new RecommendationServiceImpl(products, orders, vectors);
    }

    @Test
    void coldStartUsesAvailableCatalogueAndExcludesSource() {
        when(products.findById(1L)).thenReturn(Optional.of(product(1)));
        when(products.findRecommendationFallback(eq(7L), any())).thenReturn(List.of(product(1), product(2)));
        when(products.findRecommendationFallback(isNull(), any())).thenReturn(List.of(product(3)));
        assertThat(service.similar(1L)).extracting(item -> item.id()).containsExactly(2L, 3L);
    }

    @Test
    void staleCachedUnavailableCandidateIsRemovedByDatabaseEligibility() {
        vectors.upsert(1L, "Source", new double[]{1, 0});
        vectors.upsert(2L, "Unavailable", new double[]{1, 0});
        vectors.upsert(3L, "Available", new double[]{.8, .2});
        when(products.findById(1L)).thenReturn(Optional.of(product(1)));
        when(products.findRecommendationCandidates(any())).thenReturn(List.of(product(3)));
        assertThat(service.similar(1L)).extracting(item -> item.id()).containsExactly(3L);
    }

    @Test
    void personalizedExcludesDeliveredPurchasesAndCurrentProductEvenInFallback() {
        when(orders.findDeliveredProductIds(12L)).thenReturn(List.of(2L, 3L));
        when(products.findRecommendationFallback(isNull(), any())).thenReturn(List.of(product(1), product(2), product(3), product(4)));
        assertThat(service.personalized(12L, 1L)).extracting(item -> item.id()).containsExactly(4L);
        verify(orders).findDeliveredProductIds(12L);
    }

    @Test
    void guestPersonalizationAndMissingSourceHaveNoResults() {
        assertThat(service.personalized(null, 1L)).isEmpty();
        assertThat(service.similar(100L)).isEmpty();
        verifyNoInteractions(orders);
    }

    private Product product(long id) {
        return Product.builder().id(id).title("Product " + id).price(new BigDecimal("90000"))
                .stockQuantity(2).productType(Product.ProductType.BOOK).isActive(true)
                .category(Category.builder().id(7L).name("Books").isActive(true).build()).build();
    }
}
