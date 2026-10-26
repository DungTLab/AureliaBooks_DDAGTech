package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.SupplierForm;
import com.ddagtech.aureliabooks.dto.response.SupplierSummary;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.service.SupplierService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SupplierController.class)
@Import(SecurityConfig.class)
class SupplierControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierService supplierService;

    @MockitoBean
    private RoleRepository roleRepository;

    @Test
    @DisplayName("GET /manager/suppliers as MANAGER returns 200 OK and resolves admin/suppliers/list view")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void getSuppliers_AsManager_Returns200() throws Exception {
        SupplierSummary summary = new SupplierSummary(
                1L, "NXB Trẻ", "Nguyễn Văn A", "0912345678", "nxb@example.com", "Hà Nội", true, LocalDateTime.now(), 2L
        );
        Page<SupplierSummary> page = new PageImpl<>(List.of(summary));
        when(supplierService.list(any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/manager/suppliers"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/suppliers/list"))
                .andExpect(model().attributeExists("suppliersPage"))
                .andExpect(model().attributeExists("supplierForm"));
    }

    @Test
    @DisplayName("GET /manager/suppliers as ADMIN returns 200 OK")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void getSuppliers_AsAdmin_Returns200() throws Exception {
        when(supplierService.list(any(), any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/manager/suppliers"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/suppliers/list"));
    }

    @Test
    @DisplayName("GET /manager/suppliers as CUSTOMER returns 403 Forbidden")
    @WithMockUser(username = "customer", roles = {"CUSTOMER"})
    void getSuppliers_AsCustomer_Returns403() throws Exception {
        mockMvc.perform(get("/manager/suppliers"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /manager/suppliers unauthenticated redirects to /auth/login")
    void getSuppliers_Unauthenticated_Redirects() throws Exception {
        mockMvc.perform(get("/manager/suppliers"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/internal/login"));
    }

    @Test
    @DisplayName("POST /manager/suppliers/create with valid form redirects with success message")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void createSupplier_Valid_RedirectsWithSuccess() throws Exception {
        when(supplierService.create(any(SupplierForm.class))).thenReturn(10L);

        mockMvc.perform(post("/manager/suppliers/create")
                        .with(csrf())
                        .param("name", "Công Ty Fahasa")
                        .param("contactName", "Lê Văn C")
                        .param("phone", "0912345678")
                        .param("email", "fahasa@example.com")
                        .param("address", "TP. Hồ Chí Minh"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/suppliers"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(supplierService).create(any(SupplierForm.class));
    }

    @Test
    @DisplayName("POST /manager/suppliers/create with blank name redirects with error message")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void createSupplier_Invalid_RedirectsWithError() throws Exception {
        mockMvc.perform(post("/manager/suppliers/create")
                        .with(csrf())
                        .param("name", "") // Blank name violates @NotBlank
                        .param("phone", "0912345678"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/suppliers"))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(flash().attribute("openCreateModal", true));

        verify(supplierService, never()).create(any(SupplierForm.class));
    }

    @Test
    @DisplayName("POST /manager/suppliers/create when service throws duplicate phone error redirects with error message")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void createSupplier_DuplicatePhone_RedirectsWithError() throws Exception {
        doThrow(new AppException(ErrorCode.INVALID_INPUT_DATA, "Số điện thoại đã được đăng ký cho nhà cung cấp khác."))
                .when(supplierService).create(any(SupplierForm.class));

        mockMvc.perform(post("/manager/suppliers/create")
                        .with(csrf())
                        .param("name", "NXB Trẻ")
                        .param("phone", "0912345678"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/suppliers"))
                .andExpect(flash().attribute("errorMessage", "Số điện thoại đã được đăng ký cho nhà cung cấp khác."))
                .andExpect(flash().attribute("openCreateModal", true));
    }

    @Test
    @DisplayName("POST /manager/suppliers/{id}/update with valid form redirects with success message")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void updateSupplier_Valid_RedirectsWithSuccess() throws Exception {
        doNothing().when(supplierService).update(eq(1L), any(SupplierForm.class));

        mockMvc.perform(post("/manager/suppliers/1/update")
                        .with(csrf())
                        .param("name", "NXB Trẻ Mới")
                        .param("phone", "0912345678")
                        .param("isActive", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/suppliers"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(supplierService).update(eq(1L), any(SupplierForm.class));
    }

    @Test
    @DisplayName("POST /manager/suppliers/{id}/toggle-status redirects with success message")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void toggleStatus_RedirectsWithSuccess() throws Exception {
        doNothing().when(supplierService).toggleActive(1L, false);

        mockMvc.perform(post("/manager/suppliers/1/toggle-status")
                        .with(csrf())
                        .param("active", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/suppliers"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(supplierService).toggleActive(1L, false);
    }

    @Test
    @DisplayName("POST /manager/suppliers/{id}/delete successfully deletes when no receipts exist")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void deleteSupplier_NoReceipts_Success() throws Exception {
        doNothing().when(supplierService).delete(1L);

        mockMvc.perform(post("/manager/suppliers/1/delete")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/suppliers"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(supplierService).delete(1L);
    }

    @Test
    @DisplayName("POST /manager/suppliers/{id}/delete fails with flash error message when supplier has receipts")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void deleteSupplier_HasReceipts_FailsWithErrorMessage() throws Exception {
        doThrow(new AppException(ErrorCode.INVALID_INPUT_DATA,
                "Không thể xóa nhà cung cấp đã có chứng từ nhập hàng trong hệ thống. Vui lòng chuyển trạng thái sang Ngừng hợp tác (Inactive)."))
                .when(supplierService).delete(1L);

        mockMvc.perform(post("/manager/suppliers/1/delete")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/suppliers"))
                .andExpect(flash().attribute("errorMessage",
                        "Không thể xóa nhà cung cấp đã có chứng từ nhập hàng trong hệ thống. Vui lòng chuyển trạng thái sang Ngừng hợp tác (Inactive)."));

        verify(supplierService).delete(1L);
    }

    @Test
    @DisplayName("GET /manager/suppliers/{id} returns JSON summary")
    @WithMockUser(username = "manager", roles = {"MANAGER"})
    void getDetail_ReturnsJson() throws Exception {
        SupplierSummary summary = new SupplierSummary(
                1L, "NXB Trẻ", "Nguyễn Văn A", "0912345678", "nxb@example.com", "Hà Nội", true, LocalDateTime.now(), 2L
        );
        when(supplierService.getSummaryById(1L)).thenReturn(summary);

        mockMvc.perform(get("/manager/suppliers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("NXB Trẻ"))
                .andExpect(jsonPath("$.receiptCount").value(2));
    }
}
