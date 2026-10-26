package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.AuthorSummary;
import com.ddagtech.aureliabooks.dto.response.BrandSummary;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.PublisherSummary;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
        when(productService.getAuthors()).thenReturn(List.of(
                new AuthorSummary(1L, "Nguyễn Nhật Ánh")
        ));
        when(productService.getPublishers()).thenReturn(List.of(
                new PublisherSummary(1L, "NXB Trẻ")
        ));
        when(productService.getBrands()).thenReturn(List.of(
                new BrandSummary(1L, "Thiên Long")
        ));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"))
                .andExpect(model().attributeExists("products"))
                .andExpect(model().attributeExists("categories"))
                .andExpect(model().attributeExists("authors"))
                .andExpect(model().attributeExists("publishers"))
                .andExpect(model().attributeExists("brands"))
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
    @DisplayName("GET /products renders interactive non-hidden form controls for author, publisher, and brand")
    void getProductsRendersFacetedFilterControlsForAuthorPublisherBrand() throws Exception {
        when(productService.browse(any(), any())).thenReturn(Page.empty());
        when(productService.getActiveCategories()).thenReturn(List.of(
                new CategorySummary(1L, "Văn Học", null)
        ));
        when(productService.getAuthors()).thenReturn(List.of(
                new AuthorSummary(1L, "Nguyễn Nhật Ánh"),
                new AuthorSummary(2L, "Robert C. Martin")
        ));
        when(productService.getPublishers()).thenReturn(List.of(
                new PublisherSummary(1L, "NXB Trẻ")
        ));
        when(productService.getBrands()).thenReturn(List.of(
                new BrandSummary(1L, "Thiên Long")
        ));

        var result = mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("name=\"authorId\"");
        assertThat(html).contains("name=\"publisherId\"");
        assertThat(html).contains("name=\"brandId\"");
        assertThat(html).contains("type=\"radio\"");
        assertThat(html).doesNotContain("<input type=\"hidden\" name=\"authorId\"");
        assertThat(html).doesNotContain("<input type=\"hidden\" name=\"publisherId\"");
        assertThat(html).doesNotContain("<input type=\"hidden\" name=\"brandId\"");
        assertThat(html).contains("Nguyễn Nhật Ánh");
        assertThat(html).contains("NXB Trẻ");
        assertThat(html).contains("Thiên Long");
    }

    @Test
    @DisplayName("GET /products/search renders search template with model attributes")
    void getSearchPage() throws Exception {
        when(productService.browse(any(), any())).thenReturn(Page.empty());
        when(productService.getActiveCategories()).thenReturn(List.of());

        mockMvc.perform(get("/products/search").param("keyword", "Harry Potter"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/search"))
                .andExpect(model().attributeExists("products"))
                .andExpect(model().attribute("keyword", "Harry Potter"));
    }

    @Test
    @DisplayName("GET /products/1 renders detail template with product details")
    void getDetailPage() throws Exception {
        com.ddagtech.aureliabooks.dto.response.ProductDetailResponse detail =
                new com.ddagtech.aureliabooks.dto.response.ProductDetailResponse(
                        1L, "BARCODE123", "Harry Potter", new BigDecimal("150000"),
                        new BigDecimal("200000"), 10, "http://image.jpg", "Great book", 350,
                        Product.ProductType.BOOK, 2L, "Văn Học", "978-1234567890", 1L, "NXB Trẻ",
                        List.of("J.K. Rowling"), "Harry Potter", 1, false, 2020, "1", 350,
                        Book.CoverType.PAPERBACK, "Tiếng Việt", null, null, null, null, null
                );
        when(productService.viewDetail(1L)).thenReturn(detail);

        mockMvc.perform(get("/products/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/detail"))
                .andExpect(model().attributeExists("product"));
    }

    @Test
    @DisplayName("GET /products/autocomplete with keyword >= 2 returns suggestion list JSON")
    void getAutocompleteSuggestions() throws Exception {
        var suggestion = new com.ddagtech.aureliabooks.dto.response.ProductAutoCompleteResponse(
                1L, "Harry Potter", new BigDecimal("150000"), "http://image.jpg", "BARCODE123"
        );
        when(productService.autoComplete("Har")).thenReturn(List.of(suggestion));

        mockMvc.perform(get("/products/autocomplete").param("keyword", "Har"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("Harry Potter"))
                .andExpect(jsonPath("$[0].price").value(150000))
                .andExpect(jsonPath("$[0].imageUrl").value("http://image.jpg"))
                .andExpect(jsonPath("$[0].barcode").value("BARCODE123"));
    }

    @Test
    @DisplayName("GET /products/autocomplete with keyword < 2 returns empty list without calling service")
    void getAutocompleteShortKeywordReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/products/autocomplete").param("keyword", "H"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("GET /products/1 enforces internal-role purchase guard and customer/guest action states")
    void getDetailPageEnforcesRoleBasedPurchaseActions() throws Exception {
        com.ddagtech.aureliabooks.dto.response.ProductDetailResponse detail =
                new com.ddagtech.aureliabooks.dto.response.ProductDetailResponse(
                        1L, "BARCODE123", "Harry Potter", new BigDecimal("150000"),
                        new BigDecimal("200000"), 10, "http://image.jpg", "Great book", 350,
                        Product.ProductType.BOOK, 2L, "Văn Học", "978-1234567890", 1L, "NXB Trẻ",
                        List.of("J.K. Rowling"), "Harry Potter", 1, false, 2020, "1", 350,
                        Book.CoverType.PAPERBACK, "Tiếng Việt", null, null, null, null, null
                );
        when(productService.viewDetail(1L)).thenReturn(detail);

        // 1. Internal staff roles (ADMIN, MANAGER, SALE_STAFF) see read-only management notice and no cart form
        for (String staffRole : new String[]{"ADMIN", "MANAGER", "SALE_STAFF"}) {
            mockMvc.perform(get("/products/1").with(user("staff-viewer").roles(staffRole)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Chế độ Quản lý: Tài khoản nội bộ chỉ xem thông tin sản phẩm, không thực hiện mua hàng.")))
                    .andExpect(content().string(not(containsString("action=\"/cart/items\""))))
                    .andExpect(content().string(not(containsString("Thêm Vào Giỏ Hàng"))));
        }

        // 2. CUSTOMER sees Add to Cart form and button, without the internal staff notice
        mockMvc.perform(get("/products/1").with(user("customer-buyer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/cart/items\"")))
                .andExpect(content().string(containsString("Thêm Vào Giỏ Hàng")))
                .andExpect(content().string(not(containsString("Chế độ Quản lý:"))));

        // 3. Anonymous guest sees login prompt button instead of direct cart submission
        mockMvc.perform(get("/products/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đăng Nhập Để Mua Hàng")))
                .andExpect(content().string(not(containsString("Thêm Vào Giỏ Hàng"))))
                .andExpect(content().string(not(containsString("Chế độ Quản lý:"))));
    }
}
