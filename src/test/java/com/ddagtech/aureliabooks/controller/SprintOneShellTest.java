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
import static org.hamcrest.Matchers.not;

/** Only verifies shell rendering and existing URL boundaries, not Sprint 1 business DoD. */
@WebMvcTest(controllers = {AuthController.class, ProductController.class,
        AdminUserController.class, AdminProductController.class, MasterDataController.class,
        SupplierController.class, AdminReceiptController.class, AuditLogController.class,
        DashboardController.class})
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
    private com.ddagtech.aureliabooks.service.AdminProductService adminProductService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.MasterDataService masterDataService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.SupplierService supplierService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.AuditLogService auditLogService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.GoodsReceiptService goodsReceiptService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.repository.SupplierRepository supplierRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.repository.ProductRepository productRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.repository.CategoryRepository categoryRepository;

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
        org.mockito.Mockito.when(goodsReceiptService.getReceipts(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        org.mockito.Mockito.when(goodsReceiptService.getStockMetrics())
                .thenReturn(new com.ddagtech.aureliabooks.dto.response.StockMetricSummary(0, 0, 0, 0));
        org.mockito.Mockito.when(goodsReceiptService.getStockAlerts(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        org.mockito.Mockito.when(supplierRepository.findByIsActiveTrueOrderByNameAsc())
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(productRepository.findByIsActiveTrueOrderByTitleAsc())
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(categoryRepository.findByIsActiveTrueOrderByNameAsc())
                .thenReturn(java.util.List.of());

mvc.perform(get("/manager/suppliers").with(user("shell-review").roles("MANAGER")))
                .andExpect(status().isOk());
        mvc.perform(get("/staff/receipts/new").with(user("shell-review").roles("SALE_STAFF")))
                .andExpect(status().isOk());
        mvc.perform(get("/manager/receipts").with(user("shell-review").roles("MANAGER")))
                .andExpect(status().isOk());
        mvc.perform(get("/manager/stock/alerts").with(user("shell-review").roles("MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    void managerProductsPageRendersForManagerRole() throws Exception {
        org.mockito.Mockito.when(adminProductService.listProducts(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/manager/products").with(user("manager-tester").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quản Trị Danh Mục Sản Phẩm")));
    }

    @Test
    void managerAuthorsPageRendersForManagerRole() throws Exception {
        org.mockito.Mockito.when(masterDataService.listAuthors(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/manager/authors").with(user("manager-tester").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quản Lý Danh Bạ Tác Giả")));
    }

    @Test
    void managerPublishersPageRendersForManagerRole() throws Exception {
        org.mockito.Mockito.when(masterDataService.listPublishers(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/manager/publishers").with(user("manager-tester").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quản Lý Danh Bạ Nhà Xuất Bản")));
    }

    @Test
    void managerBrandsPageRendersForManagerRole() throws Exception {
        org.mockito.Mockito.when(masterDataService.listBrands(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/manager/brands").with(user("manager-tester").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quản Lý Danh Bạ Thương Hiệu VPP")));
    }

    @Test
    void managerCategoriesPageRendersForManagerRole() throws Exception {
        org.mockito.Mockito.when(masterDataService.getAllCategoriesWithParent())
                .thenReturn(java.util.List.of());
        mvc.perform(get("/manager/categories").with(user("manager-tester").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quản Trị Cây Danh Mục Sản Phẩm")));
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

    @Test
    void navbarAndDashboardAlignRoleVisibilityWithSecurityConfig() throws Exception {
        org.mockito.Mockito.when(productService.browse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        org.mockito.Mockito.when(productService.getActiveCategories())
                .thenReturn(java.util.List.of());

        // 1. CUSTOMER sees personal profile/address/orders links, but no back-office links
        mvc.perform(get("/products").with(user("customer-nav").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/profile\"")))
                .andExpect(content().string(containsString("href=\"/profile/addresses\"")))
                .andExpect(content().string(containsString("href=\"/orders/my-orders\"")))
                .andExpect(content().string(containsString("Cước từ 30.000đ theo vùng")))
                .andExpect(content().string(containsString("group/megamenu")))
                .andExpect(content().string(not(containsString("href=\"/manager/products\""))))
                .andExpect(content().string(not(containsString("href=\"/admin/users\""))))
                .andExpect(content().string(not(containsString("href=\"/admin/audit-logs\""))))
                .andExpect(content().string(not(containsString("href=\"/staff/orders\""))));

        // 2. ADMIN does NOT see CUSTOMER-only /profile links or MANAGER/STAFF-only links, and DOES see /admin/*
        mvc.perform(get("/dashboard").with(user("admin-nav").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/profile\""))))
                .andExpect(content().string(not(containsString("href=\"/profile/addresses\""))))
                .andExpect(content().string(not(containsString("href=\"/orders/my-orders\""))))
                .andExpect(content().string(not(containsString("href=\"/cart\""))))
                .andExpect(content().string(not(containsString("Cước từ 30.000đ theo vùng"))))
                .andExpect(content().string(not(containsString("group/megamenu"))))
                .andExpect(content().string(containsString("href=\"/admin/users\"")))
                .andExpect(content().string(containsString("href=\"/admin/audit-logs\"")))
                .andExpect(content().string(not(containsString("href=\"/manager/products\""))))
                .andExpect(content().string(not(containsString("href=\"/manager/publishers\""))))
                .andExpect(content().string(not(containsString("href=\"/manager/brands\""))))
                .andExpect(content().string(not(containsString("href=\"/manager/categories\""))))
                .andExpect(content().string(not(containsString("href=\"/manager/vouchers\""))))
                .andExpect(content().string(not(containsString("href=\"/manager/suppliers\""))))
                .andExpect(content().string(not(containsString("href=\"/manager/receipts\""))))
                .andExpect(content().string(not(containsString("href=\"/staff/receipts/new\""))))
                .andExpect(content().string(not(containsString("href=\"/staff/orders\""))));

        // 3. MANAGER does NOT see /profile links or /admin/users, and sees all /manager/* links
        mvc.perform(get("/dashboard").with(user("manager-nav").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/profile\""))))
                .andExpect(content().string(not(containsString("href=\"/admin/users\""))))
                .andExpect(content().string(not(containsString("href=\"/admin/audit-logs\""))))
                .andExpect(content().string(containsString("href=\"/manager/products\"")))
                .andExpect(content().string(containsString("href=\"/manager/publishers\"")))
                .andExpect(content().string(containsString("href=\"/manager/brands\"")))
                .andExpect(content().string(containsString("href=\"/manager/categories\"")))
                .andExpect(content().string(containsString("href=\"/manager/vouchers\"")))
                .andExpect(content().string(containsString("href=\"/manager/suppliers\"")))
                .andExpect(content().string(containsString("href=\"/manager/receipts\"")));

        // 4. SALE_STAFF on /dashboard sees /staff/orders, /staff/receipts/new, and /manager/stock/alerts, but NOT /profile or /manager/products
        mvc.perform(get("/dashboard").with(user("staff-nav").roles("SALE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/profile\""))))
                .andExpect(content().string(not(containsString("href=\"/manager/products\""))))
                .andExpect(content().string(not(containsString("href=\"/admin/users\""))))
                .andExpect(content().string(not(containsString("href=\"/admin/audit-logs\""))))
                .andExpect(content().string(containsString("href=\"/staff/orders\"")))
                .andExpect(content().string(containsString("href=\"/staff/receipts/new\"")))
                .andExpect(content().string(containsString("href=\"/manager/stock/alerts\"")))
                .andExpect(content().string(not(containsString("Cước từ 30.000đ theo vùng"))))
                .andExpect(content().string(not(containsString("group/megamenu"))));
    }
}
