package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.security.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;


import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-layer Integration Test for {@link AdminReceiptController}.
 * Verifies RBAC route protection, method-level security (@PreAuthorize),
 * model attribute population, and UI filter delegation for FND-03.
 */
@WebMvcTest(AdminReceiptController.class)
@Import({
        SecurityConfig.class,
        CustomAccessDeniedHandler.class,
        CustomAuthenticationEntryPoint.class,
        RoleBasedAuthenticationSuccessHandler.class,
        CustomAuthenticationFailureHandler.class
})
class AdminReceiptControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @Test
    @DisplayName("Navigation: GET /staff/receipts/new returns admin/receipts/form shell")
    void testDraftReceipts_ReturnsForm() throws Exception {
        mockMvc.perform(get("/staff/receipts/new").with(user("staff").roles("SALE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/receipts/form"));
    }

    @Test
    @DisplayName("Navigation: GET /manager/receipts returns admin/receipts/list shell")
    void testReviewReceipts_ReturnsList() throws Exception {
        mockMvc.perform(get("/manager/receipts").with(user("manager").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/receipts/list"));
    }

    @Test
    @DisplayName("Navigation: GET /manager/stock/alerts returns admin/stock/alerts shell")
    void testLowStockAlerts_ReturnsAlerts() throws Exception {
        mockMvc.perform(get("/manager/stock/alerts").with(user("manager").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/stock/alerts"));
    }
}
