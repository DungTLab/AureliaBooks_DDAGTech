package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.request.CategoryForm;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Brand;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Publisher;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.service.MasterDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WebMvc test suite for MasterDataController (UC17: Authors, UC18: Publishers, UC19: Brands, UC20: Categories).
 * Verifies role authorization, model attributes, view resolution, and form submissions.
 */
@WebMvcTest(controllers = MasterDataController.class)
@Import(SecurityConfig.class)
class MasterDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MasterDataService masterDataService;

    @MockitoBean
    private RoleRepository roleRepository;

    // =========================================================================
    // UC17: AUTHORS
    // =========================================================================

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("GET /manager/authors should return view with authors and book counts")
    void authors_shouldRenderView() throws Exception {
        Author author = Author.builder().id(1L).name("Nguyễn Nhật Ánh").build();
        when(masterDataService.listAuthors(any(), any())).thenReturn(new PageImpl<>(List.of(author)));
        when(masterDataService.countBooksByAuthor(1L)).thenReturn(5L);

        mockMvc.perform(get("/manager/authors"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/catalog/authors"))
                .andExpect(model().attributeExists("authorsPage", "bookCounts", "authorForm"));
    }

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("POST /manager/authors with invalid data should return view and display validation error")
    void createAuthor_withInvalidData_shouldReturnViewWithError() throws Exception {
        when(masterDataService.listAuthors(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(post("/manager/authors")
                        .with(csrf())
                        .param("name", "A") // Under 2 chars, triggers @Size(min=2)
                        .param("biography", "Short bio"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/catalog/authors"))
                .andExpect(model().hasErrors())
                .andExpect(model().attributeHasFieldErrors("authorForm", "name"))
                .andExpect(content().string(containsString("Tên tác giả phải có độ dài từ 2 đến 100 ký tự.")));
    }

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("POST /manager/authors with valid data should redirect to author list")
    void createAuthor_withValidData_shouldRedirect() throws Exception {
        when(masterDataService.createAuthor(any())).thenReturn(1L);

        mockMvc.perform(post("/manager/authors")
                        .with(csrf())
                        .param("name", "Nguyễn Nhật Ánh")
                        .param("biography", "Nhà văn nổi tiếng"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/authors"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    // =========================================================================
    // UC18: PUBLISHERS
    // =========================================================================

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("GET /manager/publishers should return view with publishers and book counts")
    void publishers_shouldRenderView() throws Exception {
        Publisher publisher = Publisher.builder().id(1L).name("NXB Trẻ").build();
        when(masterDataService.listPublishers(any(), any())).thenReturn(new PageImpl<>(List.of(publisher)));
        when(masterDataService.countBooksByPublisher(1L)).thenReturn(10L);

        mockMvc.perform(get("/manager/publishers"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/catalog/publishers"))
                .andExpect(model().attributeExists("publishersPage", "bookCounts", "publisherForm"));
    }

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("POST /manager/publishers with valid data should redirect to publisher list")
    void createPublisher_withValidData_shouldRedirect() throws Exception {
        when(masterDataService.createPublisher(any())).thenReturn(1L);

        mockMvc.perform(post("/manager/publishers")
                        .with(csrf())
                        .param("name", "NXB Trẻ")
                        .param("address", "TP.HCM")
                        .param("email", "info@nxbtre.com.vn"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/publishers"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    // =========================================================================
    // UC19: BRANDS
    // =========================================================================

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("GET /manager/brands should return view with brands and stationery counts")
    void brands_shouldRenderView() throws Exception {
        Brand brand = Brand.builder().id(1L).name("Thiên Long").originCountry("Việt Nam").build();
        when(masterDataService.listBrands(any(), any())).thenReturn(new PageImpl<>(List.of(brand)));
        when(masterDataService.countStationeriesByBrand(1L)).thenReturn(8L);

        mockMvc.perform(get("/manager/brands"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/catalog/brands"))
                .andExpect(model().attributeExists("brandsPage", "stationeryCounts", "brandForm"));
    }

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("POST /manager/brands with valid data should redirect to brand list")
    void createBrand_withValidData_shouldRedirect() throws Exception {
        when(masterDataService.createBrand(any())).thenReturn(1L);

        mockMvc.perform(post("/manager/brands")
                        .with(csrf())
                        .param("name", "Thiên Long")
                        .param("originCountry", "Việt Nam"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/brands"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    // =========================================================================
    // UC20: CATEGORIES
    // =========================================================================

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("GET /manager/categories should return view with categories tree and product counts")
    void categories_shouldRenderView() throws Exception {
        Category category = Category.builder().id(1L).name("Sách Trong Nước").build();
        when(masterDataService.getAllCategoriesWithParent()).thenReturn(List.of(category));
        when(masterDataService.countProductsByCategory(1L)).thenReturn(25L);

        mockMvc.perform(get("/manager/categories"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/catalog/categories"))
                .andExpect(model().attributeExists("categories", "productCounts", "categoryForm"));
    }

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("POST /manager/categories with valid data should redirect to category list")
    void createCategory_withValidData_shouldRedirect() throws Exception {
        when(masterDataService.createCategory(any())).thenReturn(1L);

        mockMvc.perform(post("/manager/categories")
                        .with(csrf())
                        .param("name", "Sách Trong Nước")
                        .param("description", "Danh mục sách trong nước")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/categories"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("POST /manager/categories with unchecked active should pass active=false via _active marker")
    void createCategory_withUncheckedActive_shouldPassFalse() throws Exception {
        when(masterDataService.createCategory(any())).thenReturn(2L);

        mockMvc.perform(post("/manager/categories")
                        .with(csrf())
                        .param("name", "Sách Ngoại Văn")
                        .param("_active", "on"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/categories"))
                .andExpect(flash().attributeExists("successMessage"));

        ArgumentCaptor<CategoryForm> captor = ArgumentCaptor.forClass(CategoryForm.class);
        verify(masterDataService).createCategory(captor.capture());
        assertThat(captor.getValue().active()).isFalse();
    }

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("POST /manager/categories/{id}/edit with unchecked active should bind active=false")
    void updateCategory_withUncheckedActive_shouldBindFalse() throws Exception {
        doNothing().when(masterDataService).updateCategory(eq(1L), any());

        mockMvc.perform(post("/manager/categories/1/edit")
                        .with(csrf())
                        .param("name", "Sách Trong Nước")
                        .param("_active", "on"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/categories"))
                .andExpect(flash().attributeExists("successMessage"));

        ArgumentCaptor<CategoryForm> captor = ArgumentCaptor.forClass(CategoryForm.class);
        verify(masterDataService).updateCategory(eq(1L), captor.capture());
        assertThat(captor.getValue().active()).isFalse();
    }

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("POST /manager/categories/{id}/toggle-status should redirect with success message")
    void toggleCategoryStatus_shouldRedirect() throws Exception {
        mockMvc.perform(post("/manager/categories/1/toggle-status")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/categories"))
                .andExpect(flash().attributeExists("successMessage"));
    }
}
