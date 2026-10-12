package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller integration test for {@link ProductController} (UC01 / FND-02).
 * Verifies public accessibility, HTTP status codes, view resolution,
 * model attribute population, and query parameter binding to DTOs.
 */
@WebMvcTest(controllers = ProductController.class)
@Import(SecurityConfig.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    @DisplayName("GET /products without params returns 200 OK, default pagination and model attributes")
    void getProductsDefaultParams() throws Exception {
        when(productService.browse(any(), any())).thenReturn(Page.empty());
        when(productService.getActiveCategories()).thenReturn(List.of(
                new CategorySummary(1L, "Văn Học", null)
        ));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"))
                .andExpect(model().attributeExists("products"))
                .andExpect(model().attributeExists("categories"))
                .andExpect(model().attribute("currentSort", "newest"))
                .andExpect(model().attribute("currentSize", 12));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productService).browse(any(ProductFilterRequest.class), pageableCaptor.capture());
        Pageable capturedPageable = pageableCaptor.getValue();
        assertThat(capturedPageable.getPageNumber()).isEqualTo(0);
        assertThat(capturedPageable.getPageSize()).isEqualTo(12);
    }

    @Test
    @DisplayName("GET /products with filters binds request parameters and preserves sort and size in model")
    void getProductsWithFilters() throws Exception {
        when(productService.browse(any(), any())).thenReturn(Page.empty());
        when(productService.getActiveCategories()).thenReturn(List.of());

        mockMvc.perform(get("/products")
                        .param("productType", "BOOK")
                        .param("categoryId", "2")
                        .param("minPrice", "100000")
                        .param("maxPrice", "300000")
                        .param("coverType", "PAPERBACK")
                        .param("isTextbook", "true")
                        .param("page", "2")
                        .param("size", "24")
                        .param("sort", "price_asc"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"))
                .andExpect(model().attribute("currentSort", "price_asc"))
                .andExpect(model().attribute("currentSize", 24));

        ArgumentCaptor<ProductFilterRequest> filterCaptor = ArgumentCaptor.forClass(ProductFilterRequest.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productService).browse(filterCaptor.capture(), pageableCaptor.capture());

        ProductFilterRequest filter = filterCaptor.getValue();
        assertThat(filter.productType()).isEqualTo(Product.ProductType.BOOK);
        assertThat(filter.categoryId()).isEqualTo(2L);
        assertThat(filter.minPrice()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(filter.maxPrice()).isEqualByComparingTo(new BigDecimal("300000"));
        assertThat(filter.coverType()).isEqualTo(Book.CoverType.PAPERBACK);
        assertThat(filter.isTextbook()).isTrue();

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(24);
    }

    @Test
    @DisplayName("GET /products with full filter set preserves all criteria and custom size 20")
    void getProductsWithFullFilterSet() throws Exception {
        when(productService.browse(any(), any())).thenReturn(Page.empty());
        when(productService.getActiveCategories()).thenReturn(List.of());

        mockMvc.perform(get("/products")
                        .param("productType", "BOOK")
                        .param("authorId", "7")
                        .param("publisherId", "8")
                        .param("brandId", "9")
                        .param("keyword", "Conan")
                        .param("coverType", "PAPERBACK")
                        .param("isTextbook", "true")
                        .param("page", "1")
                        .param("size", "20")
                        .param("sort", "best_sellers"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"))
                .andExpect(model().attribute("currentSort", "best_sellers"))
                .andExpect(model().attribute("currentSize", 20))
                .andExpect(model().attributeExists("filter"));

        ArgumentCaptor<ProductFilterRequest> filterCaptor = ArgumentCaptor.forClass(ProductFilterRequest.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productService).browse(filterCaptor.capture(), pageableCaptor.capture());

        ProductFilterRequest filter = filterCaptor.getValue();
        assertThat(filter.productType()).isEqualTo(Product.ProductType.BOOK);
        assertThat(filter.authorId()).isEqualTo(7L);
        assertThat(filter.publisherId()).isEqualTo(8L);
        assertThat(filter.brandId()).isEqualTo(9L);
        assertThat(filter.keyword()).isEqualTo("Conan");
        assertThat(filter.coverType()).isEqualTo(Book.CoverType.PAPERBACK);
        assertThat(filter.isTextbook()).isTrue();

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(0);
        assertThat(pageable.getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("GET /products/search renders search placeholder template")
    void getSearchPage() throws Exception {
        mockMvc.perform(get("/products/search"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/search"));
    }

    @Test
    @DisplayName("GET /products/1 renders detail placeholder template")
    void getDetailPage() throws Exception {
        mockMvc.perform(get("/products/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/detail"));
    }
}
