package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementResponse;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary.MovementType;
import com.ddagtech.aureliabooks.service.InventoryHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminStockHistoryController.class)
@Import(SecurityConfig.class)
@DisplayName("AdminStockHistoryController WebMvc & Security Tests (TASK-04)")
class AdminStockHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryHistoryService inventoryHistoryService;

    @Nested
    @DisplayName("Authorization & RBAC Security Tests")
    class SecurityRbacTests {

        @Test
        @DisplayName("GET /manager/stock/history as MANAGER returns 200 OK")
        void list_asManager_returns200() throws Exception {
            when(inventoryHistoryService.getInventoryHistory(any(), any()))
                    .thenReturn(Page.empty());

            mockMvc.perform(get("/manager/stock/history")
                            .with(user("manager").roles("MANAGER")))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/stock/history"))
                    .andExpect(model().attributeExists("movementsPage", "movementTypes", "filter"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Lịch sử Biến động Kho")));
        }

        @Test
        @DisplayName("GET /manager/stock/history as ADMIN returns 200 OK")
        void list_asAdmin_returns200() throws Exception {
            when(inventoryHistoryService.getInventoryHistory(any(), any()))
                    .thenReturn(Page.empty());

            mockMvc.perform(get("/manager/stock/history")
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/stock/history"));
        }

        @Test
        @DisplayName("GET /manager/stock/history as SALE_STAFF returns 403 Forbidden")
        void list_asStaff_returns403() throws Exception {
            mockMvc.perform(get("/manager/stock/history")
                            .with(user("staff").roles("SALE_STAFF")))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(inventoryHistoryService);
        }

        @Test
        @DisplayName("GET /manager/stock/history as CUSTOMER returns 403 Forbidden")
        void list_asCustomer_returns403() throws Exception {
            mockMvc.perform(get("/manager/stock/history")
                            .with(user("customer").roles("CUSTOMER")))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(inventoryHistoryService);
        }

        @Test
        @DisplayName("GET /manager/stock/history unauthenticated redirects to login")
        void list_unauthenticated_redirectsToLogin() throws Exception {
            mockMvc.perform(get("/manager/stock/history"))
                    .andExpect(status().is3xxRedirection());

            verifyNoInteractions(inventoryHistoryService);
        }

        @Test
        @DisplayName("GET alternate route /manager/inventory/history as MANAGER returns 200 OK")
        void list_alternateRoute_returns200() throws Exception {
            when(inventoryHistoryService.getInventoryHistory(any(), any()))
                    .thenReturn(Page.empty());

            mockMvc.perform(get("/manager/inventory/history")
                            .with(user("manager").roles("MANAGER")))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/stock/history"));
        }
    }

    @Nested
    @DisplayName("Model Binding & Form Validation Tests")
    class ModelBindingAndValidationTests {

        @Test
        @DisplayName("Binds query results to model when valid filter is submitted")
        void list_withValidFilter_bindsResultsToModel() throws Exception {
            InventoryMovementResponse sample = new InventoryMovementResponse(
                    1L, "SGK Tieng Viet 1", "8935001", MovementType.IMPORT,
                    LocalDateTime.now(), 100, "goods_receipts", 10L, 20L, "GR-10",
                    1L, "Store Manager", "mgr@store.com", "First import"
            );
            Page<InventoryMovementResponse> page = new PageImpl<>(List.of(sample), PageRequest.of(0, 20), 1);

            when(inventoryHistoryService.getInventoryHistory(any(), any()))
                    .thenReturn(page);

            mockMvc.perform(get("/manager/stock/history")
                            .with(user("manager").roles("MANAGER"))
                            .param("keyword", "Tieng Viet")
                            .param("movementType", "IMPORT")
                            .param("startDate", "2026-10-01")
                            .param("endDate", "2026-10-20")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeExists("movementsPage"))
                    .andExpect(model().attributeHasNoErrors("filter"));

            verify(inventoryHistoryService).getInventoryHistory(any(), any());
        }

        @Test
        @DisplayName("Catches chronological date error (1.0.E1) and returns 200 with binding error without failing")
        void list_withInvalidDateRange_rendersViewWithBindingError() throws Exception {
            mockMvc.perform(get("/manager/stock/history")
                            .with(user("manager").roles("MANAGER"))
                            .param("startDate", "2026-10-20")
                            .param("endDate", "2026-10-10"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/stock/history"))
                    .andExpect(model().attributeHasFieldErrors("filter", "startDate"));

            verifyNoInteractions(inventoryHistoryService);
        }
    }
}
