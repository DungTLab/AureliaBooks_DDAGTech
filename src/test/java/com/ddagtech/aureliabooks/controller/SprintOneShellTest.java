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
@WebMvcTest(controllers = {AuthController.class, ProfileController.class, ProductController.class,
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

    @Test
    void publicShellsRenderWithoutAuthentication() throws Exception {
        org.mockito.Mockito.when(productService.browse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        org.mockito.Mockito.when(productService.getActiveCategories())
                .thenReturn(java.util.List.of());
        mvc.perform(get("/auth/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Email hoặc số điện thoại")));
        for (String route : new String[]{"/products/search", "/products/1"}) {
            mvc.perform(get(route)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("Trang đang được xây dựng.")));
        }
        mvc.perform(get("/products")).andExpect(status().isOk());
    }

    @Test
    void protectedShellsRenderForAssignedRole() throws Exception {
        String[][] cases = {
                {"/profile", "CUSTOMER"}, {"/profile/addresses", "CUSTOMER"},
                {"/manager/products", "MANAGER"}, {"/manager/products/new", "MANAGER"},
                {"/manager/authors", "MANAGER"}, {"/manager/publishers", "MANAGER"},
                {"/manager/brands", "MANAGER"}, {"/manager/categories", "MANAGER"},
                {"/manager/suppliers", "MANAGER"}, {"/staff/receipts/new", "SALE_STAFF"},
                {"/manager/receipts", "MANAGER"}, {"/manager/stock/alerts", "MANAGER"},
                {"/admin/audit-logs", "ADMIN"}
        };
        for (String[] item : cases) {
            mvc.perform(get(item[0]).with(user("shell-review").roles(item[1])))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Trang đang được xây dựng.")));
        }
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
    void guestIsRedirectedAndCustomerCannotOpenManagementShells() throws Exception {
        mvc.perform(get("/profile")).andExpect(status().is3xxRedirection());
        for (String route : new String[]{"/manager/products", "/staff/receipts/new", "/admin/users"}) {
            mvc.perform(get(route).with(user("customer").roles("CUSTOMER")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/auth/register")).andExpect(status().isForbidden());
    }
}
