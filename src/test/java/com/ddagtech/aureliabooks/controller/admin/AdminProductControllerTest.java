package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.request.BookForm;
import com.ddagtech.aureliabooks.dto.request.StationeryForm;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.service.AdminProductService;
import com.ddagtech.aureliabooks.service.MasterDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WebMvc test suite for AdminProductController (UC15, UC16, UC17).
 * Verifies role authorization, model attributes, view resolution, and form binding.
 */
@WebMvcTest(controllers = AdminProductController.class)
@Import(SecurityConfig.class)
class AdminProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminProductService adminProductService;

    @MockitoBean
    private MasterDataService masterDataService;

    @MockitoBean
    private RoleRepository roleRepository;

    @Test
    @WithMockUser(roles = "MANAGER")
    void disabledR2KeepsManualUrlAndHidesUploader() throws Exception {
        mockMvc.perform(get("/manager/books/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"mainImageUrl\" type=\"text\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-image-uploader"))));
    }

    @Test
    @DisplayName("GET /manager/products as MANAGER returns 200 OK and model attributes")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void getProducts_AsManager_Returns200() throws Exception {
        Category category = Category.builder().id(1L).name("Văn Học").build();
        Product product = Product.builder()
                .id(1L)
                .barcode("893500000001")
                .title("Mắt Biếc")
                .category(category)
                .productType(Product.ProductType.BOOK)
                .price(BigDecimal.valueOf(110000))
                .originalCost(BigDecimal.valueOf(70000))
                .stockQuantity(10)
                .isActive(true)
                .build();

        Page<Product> page = new PageImpl<>(List.of(product));
        when(adminProductService.listProducts(any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/manager/products"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/products/list"))
                .andExpect(model().attributeExists("productsPage"))
                .andExpect(model().attributeExists("keyword"));

        verify(adminProductService).listProducts(isNull(), isNull(), any());
    }

    @Test
    @DisplayName("GET /manager/products as CUSTOMER returns 403 Forbidden")
    @WithMockUser(username = "customer", roles = {"CUSTOMER"})
    void getProducts_AsCustomer_Returns403() throws Exception {
        mockMvc.perform(get("/manager/products"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminProductService);
    }

    @Test
    @DisplayName("POST /manager/products/{id}/toggle-status toggles active status and redirects")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void toggleStatus_AsManager_RedirectsWithSuccess() throws Exception {
        doNothing().when(adminProductService).toggleActive(100L);

        mockMvc.perform(post("/manager/products/100/toggle-status").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/products"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(adminProductService).toggleActive(100L);
    }

    @Test
    @DisplayName("POST /manager/products/{id}/toggle-status without CSRF returns 403")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void toggleStatus_WithoutCsrf_Returns403() throws Exception {
        mockMvc.perform(post("/manager/products/100/toggle-status"))
                .andExpect(status().isForbidden());

        verify(adminProductService, never()).toggleActive(any());
    }

    @Test
    @DisplayName("Black-Box HTTP Test: POST /manager/books with lowercase x inside ISBN-13 is rejected with field error")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void createBook_WithLowercaseXInsideIsbn13_ReturnsFormWithFieldError() throws Exception {
        mockMvc.perform(post("/manager/books")
                .with(csrf())
                .param("barcode", "893500000001")
                .param("title", "Sách Lỗi ISBN")
                .param("categoryId", "1")
                .param("price", "100000")
                .param("originalCost", "60000")
                .param("weightGrams", "200")
                .param("isbn", "978x0306406157") // Reviewer witness: lowercase x inside ISBN-13
                .param("publisherId", "1")
                .param("authorIds", "1")
                .param("pageCount", "300"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/products/book-form"))
                .andExpect(model().attributeHasFieldErrors("bookForm", "isbn"));

        verify(adminProductService, never()).createBook(any());
    }

    @Test
    @DisplayName("Black-Box HTTP Test: POST /manager/books with valid ISBN-10 check digit X is accepted and calls service")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void createBook_WithValidIsbn10CheckDigitX_SucceedsAndCallsService() throws Exception {
        when(adminProductService.createBook(any(BookForm.class))).thenReturn(100L);

        mockMvc.perform(post("/manager/books")
                .with(csrf())
                .param("barcode", "893500000001")
                .param("title", "Sách Hợp Lệ ISBN-10")
                .param("categoryId", "1")
                .param("price", "100000")
                .param("originalCost", "60000")
                .param("weightGrams", "200")
                .param("isbn", "080442957X") // Valid ISBN-10 check digit X
                .param("publisherId", "1")
                .param("authorIds", "1")
                .param("pageCount", "300"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/products?type=BOOK"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(adminProductService).createBook(any(BookForm.class));
    }
}
