package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.service.MasterDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WebMvc test suite for MasterDataController (UC17: Authors).
 * Verifies role authorization, model attributes, view resolution, and validation errors.
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

    @Test
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    @DisplayName("POST /manager/authors/{id}/toggle-status should toggle status and redirect")
    void toggleAuthorStatus_shouldRedirect() throws Exception {
        doNothing().when(masterDataService).toggleAuthorStatus(1L);

        mockMvc.perform(post("/manager/authors/1/toggle-status")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/authors"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(masterDataService).toggleAuthorStatus(1L);
    }
}
