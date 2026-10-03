package com.ddagtech.aureliabooks.security;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test suite for {@link SecurityConfig} and Spring Security Filter Chain.
 * Validates Lean RBAC route protection, No Guest Cart invariant, and hybrid 401/403 handlers.
 */
@WebMvcTest(controllers = {HomeController.class, CustomErrorController.class})
@Import({
        SecurityConfig.class,
        CustomAccessDeniedHandler.class,
        CustomAuthenticationEntryPoint.class,
        RoleBasedAuthenticationSuccessHandler.class,
        CustomAuthenticationFailureHandler.class
})
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @Test
    @DisplayName("Should permit anonymous access to public storefront homepage (/)")
    void testPublicStorefrontAccess_RootUrl() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    @DisplayName("Should permit anonymous access to public storefront home (/home)")
    void testPublicStorefrontAccess_HomeUrl() throws Exception {
        mockMvc.perform(get("/home"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    @DisplayName("QA-01: Should redirect anonymous user to login when accessing exact /admin root")
    void testUnauthenticatedAccess_AdminRoot_RedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    @DisplayName("Should redirect anonymous user to login when accessing protected admin console")
    void testUnauthenticatedAccess_AdminRoute_RedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    @DisplayName("Should enforce No Guest Cart invariant (BR-03-01): redirect anonymous checkout to login")
    void testUnauthenticatedAccess_CheckoutRoute_RedirectsToLogin() throws Exception {
        mockMvc.perform(get("/checkout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    @DisplayName("Should enforce No Guest Cart invariant (BR-03-01): redirect anonymous cart to login")
    void testUnauthenticatedAccess_CartRoute_RedirectsToLogin() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    @DisplayName("QA-02: Should redirect authenticated CUSTOMER to /error/403 when accessing exact /admin root")
    void testRbacForbidden_CustomerAccessingAdminRoot_RedirectsTo403() throws Exception {
        mockMvc.perform(get("/admin").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("Should redirect authenticated CUSTOMER to /error/403 when accessing /admin/users")
    void testRbacForbidden_CustomerAccessingAdmin_RedirectsTo403() throws Exception {
        mockMvc.perform(get("/admin/users").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("Should redirect authenticated CUSTOMER to /error/403 when accessing /manager/books")
    void testRbacForbidden_CustomerAccessingManager_RedirectsTo403() throws Exception {
        mockMvc.perform(get("/manager/books").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("Should permit authenticated ADMIN to pass security filter on /admin/users")
    void testRbacAuthorized_AdminAccessingAdminRoute() throws Exception {
        // Since Admin controller is not yet registered in this slice, Spring resolves NoResourceFoundException
        // and redirects to /error/404, confirming authorization passed without 401 or 403
        mockMvc.perform(get("/admin/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/404"));
    }

    @Test
    @DisplayName("Should return 401 JSON ApiResponse when unauthenticated AJAX requests a protected route")
    void testAjaxUnauthenticated_ReturnsJson401() throws Exception {
        mockMvc.perform(get("/admin/users").header("Accept", "application/json"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("Unauthenticated access. Please log in"));
    }

    @Test
    @DisplayName("Should return 403 JSON ApiResponse when authenticated CUSTOMER AJAX requests admin route")
    void testAjaxAccessDenied_ReturnsJson403() throws Exception {
        mockMvc.perform(get("/admin/users")
                        .with(user("customer").roles("CUSTOMER"))
                        .header("Accept", "application/json"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("You do not have permission to access this resource"));
    }

    @Test
    @DisplayName("Should permit public access to render 403 error page")
    void testErrorPage_403() throws Exception {
        mockMvc.perform(get("/error/403"))
                .andExpect(status().isOk())
                .andExpect(view().name("error/403"))
                .andExpect(model().attributeExists("title"));
    }

    @Test
    @DisplayName("Should permit public access to render 404 error page")
    void testErrorPage_404() throws Exception {
        mockMvc.perform(get("/error/404"))
                .andExpect(status().isOk())
                .andExpect(view().name("error/404"))
                .andExpect(model().attributeExists("title"));
    }

    @Test
    @DisplayName("Should permit public access to render 500 error page")
    void testErrorPage_500() throws Exception {
        mockMvc.perform(get("/error/500"))
                .andExpect(status().isOk())
                .andExpect(view().name("error/500"))
                .andExpect(model().attributeExists("title"));
    }

    @Test
    @DisplayName("QA-03: Should reject browser POST request without valid CSRF token with HTTP 403 Forbidden forward")
    void testCsrfProtection_BrowserPostWithoutCsrfToken_Forbidden403Forward() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .param("username", "test@aureliabook.vn")
                        .param("password", "Password123!"))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/error/403"));
    }

    @Test
    @DisplayName("QA-03: Should reject browser POST request with invalid CSRF token with HTTP 403 Forbidden forward")
    void testCsrfProtection_BrowserPostWithInvalidCsrfToken_Forbidden403Forward() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .with(csrf().useInvalidToken())
                        .param("username", "test@aureliabook.vn")
                        .param("password", "Password123!"))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/error/403"));
    }

    @Test
    @DisplayName("QA-03: Should reject AJAX POST request without valid CSRF token with HTTP 403 Forbidden JSON identifying CSRF missing")
    void testCsrfProtection_AjaxPostWithoutCsrfToken_Forbidden403Json() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .header("Accept", "application/json")
                        .param("username", "test@aureliabook.vn")
                        .param("password", "Password123!"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CSRF token is missing")));
    }

    @Test
    @DisplayName("QA-03: Should accept state-changing POST request when accompanied by valid CSRF token")
    void testCsrfProtection_PostWithValidCsrfToken_Processed() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .param("username", "test@aureliabook.vn")
                        .param("password", "Password123!"))
                .andExpect(status().is3xxRedirection());
    }
}
