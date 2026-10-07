package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.impl.ProductServiceImpl;
import com.ddagtech.aureliabooks.util.PageableUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for {@link ProductServiceImpl} (FND-02 & UC01).
 * Verifies filter building, pagination delegation, and entity-to-DTO mapping.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productRepository, categoryRepository);
    }

    @Test
    @DisplayName("Should browse products with default active status filter and map to ProductSummary")
    void shouldBrowseProductsWithDefaultFilters() {
        // Arrange
        Pageable pageable = PageableUtils.create(1, 12, "newest");
        Product product = Product.builder()
                .id(1L)
                .title("Clean Code")
                .price(new BigDecimal("350000.00"))
                .stockQuantity(15)
                .mainImageUrl("https://example.com/clean-code.jpg")
                .isActive(true)
                .productType(Product.ProductType.BOOK)
                .build();
        Page<Product> productPage = new PageImpl<>(List.of(product), pageable, 1);

        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(productPage);

        // Act
        Page<ProductSummary> result = productService.browse(null, pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        ProductSummary summary = result.getContent().get(0);
        assertThat(summary.id()).isEqualTo(1L);
        assertThat(summary.title()).isEqualTo("Clean Code");
        assertThat(summary.price()).isEqualByComparingTo(new BigDecimal("350000.00"));
        assertThat(summary.stockQuantity()).isEqualTo(15);
        assertThat(summary.mainImageUrl()).isEqualTo("https://example.com/clean-code.jpg");

        verify(productRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @DisplayName("Should browse products with comprehensive filters including productType and isTextbook")
    void shouldBrowseProductsWithAllFilters() {
        // Arrange
        Pageable pageable = PageableUtils.create(1, 20, "price_asc");
        ProductFilterRequest filter = new ProductFilterRequest(
                "Java",
                5L,
                new BigDecimal("50000"),
                new BigDecimal("200000"),
                10L,
                20L,
                null,
                Book.CoverType.PAPERBACK,
                Product.ProductType.BOOK,
                true
        );

        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty());

        // Act
        Page<ProductSummary> result = productService.browse(filter, pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
        verify(productRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @DisplayName("Should browse products for stationery items with brand filter")
    void shouldBrowseStationeryProducts() {
        // Arrange
        Pageable pageable = PageableUtils.create(1, 12, "best_sellers");
        ProductFilterRequest filter = new ProductFilterRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                7L,
                null,
                Product.ProductType.STATIONERY,
                null
        );

        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty());

        // Act
        Page<ProductSummary> result = productService.browse(filter, pageable);

        // Assert
        assertThat(result).isNotNull();
        verify(productRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @DisplayName("Should return active categories mapped to CategorySummary")
    void shouldReturnActiveCategories() {
        // Arrange
        Category parentCat = new Category();
        parentCat.setId(1L);
        parentCat.setName("Sách Trong Nước");
        parentCat.setIsActive(true);

        Category childCat = new Category();
        childCat.setId(2L);
        childCat.setName("Văn Học");
        childCat.setIsActive(true);
        childCat.setParent(parentCat);

        Category inactiveCat = new Category();
        inactiveCat.setId(3L);
        inactiveCat.setName("Danh Mục Cũ");
        inactiveCat.setIsActive(false);

        when(categoryRepository.findAll()).thenReturn(List.of(parentCat, childCat, inactiveCat));

        // Act
        List<CategorySummary> activeCategories = productService.getActiveCategories();

        // Assert
        assertThat(activeCategories).hasSize(2);
        assertThat(activeCategories.get(0).id()).isEqualTo(1L);
        assertThat(activeCategories.get(0).parentId()).isNull();
        assertThat(activeCategories.get(1).id()).isEqualTo(2L);
        assertThat(activeCategories.get(1).parentId()).isEqualTo(1L);
    }
}
