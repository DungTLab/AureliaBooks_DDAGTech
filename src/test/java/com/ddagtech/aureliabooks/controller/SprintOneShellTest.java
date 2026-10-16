package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.admin.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

/** Only verifies shell rendering and existing URL boundaries, not Sprint 1 business DoD. */
@WebMvcTest(controllers = {AuthController.class, ProductController.class,
        AdminUserController.class, AdminProductController.class, MasterDataController.class,
        SupplierController.class, AdminReceiptController.class, AuditLogController.class})
@Import(SecurityConfig.class)
@org.springframework.test.context.bean.override.mockito.MockitoBean(types = com.ddagtech.aureliabooks.repository.RoleRepository.class)
class SprintOneShellTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.RegistrationService registration;
    @Autowired
    private MockMvc mvc;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.AdminUserService adminUserService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.ProductService productService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.SupplierService supplierService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.AuditLogService auditLogService;

    @Test
    void publicShellsRenderWithoutAuthentication() throws Exception {
        org.mockito.Mockito.when(productService.browse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        org.mockito.Mockito.when(productService.getActiveCategories())
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(productService.viewDetail(1L))
                .thenReturn(new com.ddagtech.aureliabooks.dto.response.ProductDetailResponse(
                        1L, "SKU1", "Test Book", java.math.BigDecimal.valueOf(100000), null, 10,
                        null, "Desc", 200, com.ddagtech.aureliabooks.entity.Product.ProductType.BOOK,
                        1L, "Cat", "ISBN1", 1L, "Pub", java.util.List.of("Author"), "Series", 1, false,
                        2023, "1", 200, com.ddagtech.aureliabooks.entity.Book.CoverType.PAPERBACK, "Tiếng Việt",
                        null, null, null, null, null
                ));

        mvc.perform(get("/auth/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Email hoặc số điện thoại")));
        mvc.perform(get("/products/search")).andExpect(status().isOk());
        mvc.perform(get("/products/1")).andExpect(status().isOk());
        mvc.perform(get("/products")).andExpect(status().isOk());
    }

    @Test
    void protectedShellsRenderForAssignedRole() throws Exception {
        org.mockito.Mockito.when(supplierService.list(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        String[][] cases = {
                {"/manager/products", "MANAGER"}, {"/manager/products/new", "MANAGER"},
                {"/manager/authors", "MANAGER"}, {"/manager/publishers", "MANAGER"},
                {"/manager/brands", "MANAGER"}, {"/manager/categories", "MANAGER"},
                {"/staff/receipts/new", "SALE_STAFF"},
                {"/manager/receipts", "MANAGER"}, {"/manager/stock/alerts", "MANAGER"}
        };
        for (String[] item : cases) {
            mvc.perform(get(item[0]).with(user("shell-review").roles(item[1])))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Trang đang được xây dựng.")));
        }

        mvc.perform(get("/manager/suppliers").with(user("shell-review").roles("MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    void adminUsersPageRendersForAdminRole() throws Exception {
        org.mockito.Mockito.when(adminUserService.list(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/admin/users").with(user("admin-tester").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quản lý tài khoản nội bộ")));
    }

    @Test
    void adminAuditLogsPageRendersForAdminRole() throws Exception {
        org.mockito.Mockito.when(auditLogService.searchLogs(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        org.mockito.Mockito.when(auditLogService.getDistinctActions()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(auditLogService.getDistinctTargetTables()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(auditLogService.getDistinctActors()).thenReturn(java.util.List.of());

        mvc.perform(get("/admin/audit-logs").with(user("admin-tester").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void guestIsRedirectedAndCustomerCannotOpenManagementShells() throws Exception {
        mvc.perform(get("/profile")).andExpect(status().is3xxRedirection());
        for (String route : new String[]{"/manager/products", "/staff/receipts/new", "/admin/users", "/admin/audit-logs"}) {
            mvc.perform(get(route).with(user("customer").roles("CUSTOMER")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/auth/register")).andExpect(status().isForbidden());
    }
}
