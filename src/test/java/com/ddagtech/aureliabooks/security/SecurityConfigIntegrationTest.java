package com.ddagtech.aureliabooks.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.CustomErrorController;
import com.ddagtech.aureliabooks.controller.HomeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test suite for {@link SecurityConfig} and Spring Security Filter Chain.
 * Validates Lean RBAC route protection, No Guest Cart invariant, and hybrid 401/403 handlers.
 */
@WebMvcTest(controllers = { HomeController.class, CustomErrorController.class })
@Import({

    SecurityConfig.class,
    CustomAccessDeniedHandler.class,
    CustomAuthenticationEntryPoint.class,
    RoleBasedAuthenticationSuccessHandler.class,
    CustomAuthenticationFailureHandler.class,
})
class SecurityConfigIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.ProductService homepageProducts;

    @org.junit.jupiter.api.BeforeEach
    void setupHomepageCatalogue() {
        org.mockito.Mockito.when(homepageProducts.browse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        org.mockito.Mockito.when(homepageProducts.browse(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
    }


    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @Test
    @DisplayName("Should permit anonymous access to public storefront homepage (/)")
    void testPublicStorefrontAccess_RootUrl() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("index"));
    }

    @Test
    @DisplayName("Should permit anonymous access to public storefront home (/home)")
    void testPublicStorefrontAccess_HomeUrl() throws Exception {
        mockMvc.perform(get("/home")).andExpect(status().isOk()).andExpect(view().name("index"));
    }

    @Test
    @DisplayName("QA-01: Should redirect anonymous user to login when accessing exact /admin root")
    void testUnauthenticatedAccess_AdminRoot_RedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/internal/login"));
    }

    @Test
    @DisplayName("Should redirect anonymous user to login when accessing protected admin console")
    void testUnauthenticatedAccess_AdminRoute_RedirectsToLogin() throws Exception {
        mockMvc
            .perform(get("/admin/users"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/login"));
    }

    @Test
    @DisplayName("Should enforce No Guest Cart invariant (BR-03-01): redirect anonymous checkout to login")
    void testUnauthenticatedAccess_CheckoutRoute_RedirectsToLogin() throws Exception {
        mockMvc
            .perform(get("/checkout"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    @DisplayName("Should enforce No Guest Cart invariant (BR-03-01): redirect anonymous cart to login")
    void testUnauthenticatedAccess_CartRoute_RedirectsToLogin() throws Exception {
        mockMvc.perform(get("/cart")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    @DisplayName("QA-02: Should redirect authenticated CUSTOMER to /error/403 when accessing exact /admin root")
    void testRbacForbidden_CustomerAccessingAdminRoot_RedirectsTo403() throws Exception {
        mockMvc
            .perform(get("/admin").with(user("customer").roles("CUSTOMER")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("Should redirect authenticated CUSTOMER to /error/403 when accessing /admin/users")
    void testRbacForbidden_CustomerAccessingAdmin_RedirectsTo403() throws Exception {
        mockMvc
            .perform(get("/admin/users").with(user("customer").roles("CUSTOMER")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("Should redirect authenticated CUSTOMER to /error/403 when accessing /manager/books")
    void testRbacForbidden_CustomerAccessingManager_RedirectsTo403() throws Exception {
        mockMvc
            .perform(get("/manager/books").with(user("customer").roles("CUSTOMER")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("Should permit authenticated ADMIN to pass security filter on /admin/users")
    void testRbacAuthorized_AdminAccessingAdminRoute() throws Exception {
        // Since Admin controller is not yet registered in this slice, Spring resolves NoResourceFoundException
        // and redirects to /error/404, confirming authorization passed without 401 or 403
        mockMvc
            .perform(get("/admin/users").with(user("admin").roles("ADMIN")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/404"));
    }

    @Test
    @DisplayName("Should return 401 JSON ApiResponse when unauthenticated AJAX requests a protected route")
    void testAjaxUnauthenticated_ReturnsJson401() throws Exception {
        mockMvc
            .perform(get("/admin/users").header("Accept", "application/json"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
            .andExpect(jsonPath("$.code").value(1001))
            .andExpect(jsonPath("$.message").value("Bạn cần đăng nhập để tiếp tục."));
    }

    @Test
    @DisplayName("Should return 403 JSON ApiResponse when authenticated CUSTOMER AJAX requests admin route")
    void testAjaxAccessDenied_ReturnsJson403() throws Exception {
        mockMvc
            .perform(get("/admin/users").with(user("customer").roles("CUSTOMER")).header("Accept", "application/json"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
            .andExpect(jsonPath("$.code").value(1002))
            .andExpect(jsonPath("$.message").value("Bạn không có quyền truy cập nội dung này."));
    }

    @Test
    @DisplayName("Should permit public access to render 403 error page")
    void testErrorPage_403() throws Exception {
        mockMvc
            .perform(get("/error/403"))
            .andExpect(status().isOk())
            .andExpect(view().name("error/403"))
            .andExpect(model().attributeExists("title"));
    }

    @Test
    @DisplayName("Should permit public access to render 404 error page")
    void testErrorPage_404() throws Exception {
        mockMvc
            .perform(get("/error/404"))
            .andExpect(status().isOk())
            .andExpect(view().name("error/404"))
            .andExpect(model().attributeExists("title"));
    }

    @Test
    @DisplayName("Should permit public access to render 500 error page")
    void testErrorPage_500() throws Exception {
        mockMvc
            .perform(get("/error/500"))
            .andExpect(status().isOk())
            .andExpect(view().name("error/500"))
            .andExpect(model().attributeExists("title"));
    }

    @Test
    @DisplayName("QA-03: Should reject browser POST request without valid CSRF token with HTTP 403 Forbidden forward")
    void testCsrfProtection_BrowserPostWithoutCsrfToken_Forbidden403Forward() throws Exception {
        mockMvc
            .perform(post("/auth/login").param("username", "test@aureliabook.vn").param("password", "Password123!"))
            .andExpect(status().isForbidden())
            .andExpect(forwardedUrl("/error/403"));
    }

    @Test
    @DisplayName("QA-03: Should reject browser POST request with invalid CSRF token with HTTP 403 Forbidden forward")
    void testCsrfProtection_BrowserPostWithInvalidCsrfToken_Forbidden403Forward() throws Exception {
        mockMvc
            .perform(
                post("/auth/login")
                    .with(csrf().useInvalidToken())
                    .param("username", "test@aureliabook.vn")
                    .param("password", "Password123!")
            )
            .andExpect(status().isForbidden())
            .andExpect(forwardedUrl("/error/403"));
    }

    @Test
    @DisplayName(
        "QA-03: Should reject AJAX POST request without valid CSRF token with HTTP 403 Forbidden JSON identifying CSRF missing"
    )
    void testCsrfProtection_AjaxPostWithoutCsrfToken_Forbidden403Json() throws Exception {
        mockMvc
            .perform(
                post("/auth/login")
                    .header("Accept", "application/json")
                    .param("username", "test@aureliabook.vn")
                    .param("password", "Password123!")
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(1002))
            .andExpect(jsonPath("$.message").value("Thiếu mã bảo vệ biểu mẫu. Vui lòng tải lại trang và thử lại."));
    }

    @Test
    @DisplayName("QA-03: Should accept state-changing POST request when accompanied by valid CSRF token")
    void testCsrfProtection_PostWithValidCsrfToken_Processed() throws Exception {
        org.mockito.Mockito.when(userDetailsService.loadUserByUsername("test@aureliabook.vn")).thenThrow(
            new org.springframework.security.core.userdetails.UsernameNotFoundException("missing")
        );
        mockMvc
            .perform(
                post("/auth/login")
                    .with(csrf())
                    .param("username", "test@aureliabook.vn")
                    .param("password", "Password123!")
            )
            .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("RBAC: Authenticated ADMIN accessing /manager/products is redirected to /error/403")
    void testRbacForbidden_AdminAccessingManagerProducts_RedirectsTo403() throws Exception {
        mockMvc
            .perform(get("/manager/products").with(user("admin").roles("ADMIN")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("RBAC: Authenticated ADMIN accessing /staff/orders is redirected to /error/403")
    void testRbacForbidden_AdminAccessingStaffOrders_RedirectsTo403() throws Exception {
        mockMvc
            .perform(get("/staff/orders").with(user("admin").roles("ADMIN")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("RBAC: Authenticated ADMIN attempting to finalize receipt returns 403 Forbidden")
    void testRbacForbidden_AdminFinalizingReceipt_Returns403Json() throws Exception {
        mockMvc
            .perform(
                post("/manager/receipts/1/finalize")
                    .with(csrf())
                    .with(user("admin").roles("ADMIN"))
                    .header("Accept", "application/json")
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    @DisplayName("RBAC: Authenticated SALE_STAFF accessing /manager/products is redirected to /error/403")
    void testRbacForbidden_StaffAccessingManagerProducts_RedirectsTo403() throws Exception {
        mockMvc
            .perform(get("/manager/products").with(user("staff").roles("SALE_STAFF")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("RBAC: Authenticated SALE_STAFF accessing /manager/stock/history is redirected to /error/403")
    void testRbacForbidden_StaffAccessingStockHistory_RedirectsTo403() throws Exception {
        mockMvc
            .perform(get("/manager/stock/history").with(user("staff").roles("SALE_STAFF")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("RBAC: Authenticated SALE_STAFF attempting to finalize receipt returns 403 Forbidden")
    void testRbacForbidden_StaffFinalizingReceipt_Returns403Json() throws Exception {
        mockMvc
            .perform(
                post("/manager/receipts/1/finalize")
                    .with(csrf())
                    .with(user("staff").roles("SALE_STAFF"))
                    .header("Accept", "application/json")
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    @DisplayName("RBAC: Authenticated MANAGER accessing /admin/users is redirected to /error/403")
    void testRbacForbidden_ManagerAccessingAdminUsers_RedirectsTo403() throws Exception {
        mockMvc
            .perform(get("/admin/users").with(user("manager").roles("MANAGER")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("RBAC: Authenticated SALE_STAFF passes security filter on /staff/orders")
    void testRbacAuthorized_StaffAccessingStaffOrders() throws Exception {
        mockMvc
            .perform(get("/staff/orders").with(user("staff").roles("SALE_STAFF")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/404"));
    }

    @Test
    @DisplayName("RBAC: Authenticated MANAGER passes security filter on /manager/products")
    void testRbacAuthorized_ManagerAccessingManagerProducts() throws Exception {
        mockMvc
            .perform(get("/manager/products").with(user("manager").roles("MANAGER")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/404"));
    }

    @Test
    @DisplayName("RBAC: Authenticated MANAGER passes security filter on POST /manager/receipts/1/finalize")
    void testRbacAuthorized_ManagerFinalizingReceipt() throws Exception {
        mockMvc
            .perform(
                post("/manager/receipts/1/finalize")
                    .with(csrf())
                    .with(user("manager").roles("MANAGER"))
            )
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/error/404"));
    }

    @Test
    @DisplayName("RBAC: Authenticated ADMIN passes security filter on GET /api/receipts/1 for UC25 stock history audit")
    void testRbacAuthorized_AdminAccessingReceiptDetailApi() throws Exception {
        mockMvc
            .perform(get("/api/receipts/1").with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    @DisplayName("RBAC: Authenticated CUSTOMER accessing /api/receipts/1 is rejected with 403 Forbidden")
    void testRbacForbidden_CustomerAccessingReceiptDetailApi_Returns403Json() throws Exception {
        mockMvc
            .perform(get("/api/receipts/1").with(user("customer").roles("CUSTOMER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(1002));
    }
}