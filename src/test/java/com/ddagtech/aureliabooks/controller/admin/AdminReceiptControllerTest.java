package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.response.StockMovementLogResponse;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.StockMovementLog;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.security.*;
import com.ddagtech.aureliabooks.service.StockLedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

    @MockitoBean
    private StockLedgerService stockLedgerService;

    @MockitoBean
    private ProductRepository productRepository;

    @Test
    @DisplayName("RBAC: Unauthenticated access to /manager/stock/ledger should redirect to /auth/login")
    void testLedger_Unauthenticated_RedirectsToLogin() throws Exception {
        mockMvc.perform(get("/manager/stock/ledger"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    @DisplayName("RBAC: Authenticated CUSTOMER accessing /manager/stock/ledger should redirect to 403 Forbidden")
    void testLedger_CustomerRole_RedirectsTo403() throws Exception {
        mockMvc.perform(get("/manager/stock/ledger").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("RBAC: Authenticated SALE_STAFF accessing /manager/stock/ledger should redirect to 403 Forbidden")
    void testLedger_SaleStaffRole_RedirectsTo403() throws Exception {
        mockMvc.perform(get("/manager/stock/ledger").with(user("salestaff").roles("SALE_STAFF")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("RBAC & UI: Authenticated MANAGER accessing /manager/stock/ledger should return 200 OK and populated model")
    void testLedger_ManagerRole_SucceedsWith200AndPopulatesModel() throws Exception {
        Product book = Product.builder().id(100L).title("Đắc Nhân Tâm").barcode("893521").stockQuantity(50).build();
        when(productRepository.findAll()).thenReturn(List.of(book));

        StockMovementLogResponse logResponse = StockMovementLogResponse.builder()
                .id(1L)
                .productTitle("Đắc Nhân Tâm")
                .transactionType(StockMovementLog.TransactionType.IMPORT)
                .quantityChange(50)
                .previousStock(0)
                .currentStock(50)
                .referenceCode("GRN-01")
                .performedByUserName("Nguyễn Trần Đức Anh")
                .createdAt(LocalDateTime.now())
                .build();

        Page<StockMovementLogResponse> pagedResponse = new PageImpl<>(List.of(logResponse));
        when(stockLedgerService.getLedgerLogsWithFilter(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(pagedResponse);

        mockMvc.perform(get("/manager/stock/ledger").with(user("manager").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/stock/ledger"))
                .andExpect(model().attributeExists("logs"))
                .andExpect(model().attributeExists("products"));
    }

    @Test
    @DisplayName("RBAC: Authenticated ADMIN accessing /manager/stock/ledger should return 200 OK")
    void testLedger_AdminRole_SucceedsWith200() throws Exception {
        when(productRepository.findAll()).thenReturn(List.of());
        when(stockLedgerService.getLedgerLogsWithFilter(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/manager/stock/ledger").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/stock/ledger"))
                .andExpect(model().attributeExists("logs"))
                .andExpect(model().attributeExists("products"));
    }

    @Test
    @DisplayName("Filter params: /manager/stock/ledger with type & productId filters passes correct args to service")
    void testLedger_WithFilterParams_DelegatesCorrectly() throws Exception {
        when(productRepository.findAll()).thenReturn(List.of());
        when(stockLedgerService.getLedgerLogsWithFilter(
                eq(StockMovementLog.TransactionType.ORDER_DEDUCT), eq(100L), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/manager/stock/ledger")
                        .with(user("manager").roles("MANAGER"))
                        .param("type", "ORDER_DEDUCT")
                        .param("productId", "100")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/stock/ledger"))
                .andExpect(model().attribute("selectedType", StockMovementLog.TransactionType.ORDER_DEDUCT))
                .andExpect(model().attribute("selectedProductId", 100L));

        verify(stockLedgerService).getLedgerLogsWithFilter(
                eq(StockMovementLog.TransactionType.ORDER_DEDUCT), eq(100L), any(), any(), any(Pageable.class));
    }

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
