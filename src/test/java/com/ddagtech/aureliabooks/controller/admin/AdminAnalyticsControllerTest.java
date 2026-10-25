package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.service.AnalyticsService;
import com.ddagtech.aureliabooks.service.ExcelExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminAnalyticsController.class)
@Import(SecurityConfig.class)
@DisplayName("UC26, UC27: AdminAnalyticsController WebMvc & RBAC Security Tests")
class AdminAnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyticsService analyticsService;

    @MockitoBean
    private ExcelExportService excelExportService;

    @Test
    @WithMockUser(username = "manager@aureliabook.vn", roles = {"MANAGER"})
    @DisplayName("ROLE_MANAGER can access revenue analytics dashboard (HTTP 200)")
    void manager_canAccessRevenueDashboard() throws Exception {
        RevenueAnalyticsReportDto mockReport = RevenueAnalyticsReportDto.builder()
                .filter(new RevenueFilterRequest())
                .kpi(RevenueSummaryKpiDto.builder().totalOrders(5).grossRevenue(BigDecimal.valueOf(1500000)).build())
                .build();

        when(analyticsService.getRevenueReport(any())).thenReturn(mockReport);

        mockMvc.perform(get("/manager/analytics/revenue"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/analytics/revenue-report"))
                .andExpect(model().attributeExists("report"))
                .andExpect(model().attributeExists("filter"));
    }

    @Test
    @WithMockUser(username = "admin@aureliabook.vn", roles = {"ADMIN"})
    @DisplayName("ROLE_ADMIN can access revenue analytics dashboard (HTTP 200)")
    void admin_canAccessRevenueDashboard() throws Exception {
        RevenueAnalyticsReportDto mockReport = RevenueAnalyticsReportDto.builder()
                .filter(new RevenueFilterRequest())
                .kpi(RevenueSummaryKpiDto.builder().totalOrders(10).grossRevenue(BigDecimal.valueOf(3000000)).build())
                .build();

        when(analyticsService.getRevenueReport(any())).thenReturn(mockReport);

        mockMvc.perform(get("/admin/analytics/revenue"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/analytics/revenue-report"))
                .andExpect(model().attributeExists("report"));
    }

    @Test
    @WithMockUser(username = "staff@aureliabook.vn", roles = {"SALE_STAFF"})
    @DisplayName("ROLE_SALE_STAFF is blocked from revenue analytics (HTTP 403 Forbidden)")
    void saleStaff_isForbidden() throws Exception {
        mockMvc.perform(get("/manager/analytics/revenue"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("ROLE_CUSTOMER is blocked from revenue analytics (HTTP 403 Forbidden)")
    void customer_isForbidden() throws Exception {
        mockMvc.perform(get("/manager/analytics/revenue"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Anonymous user is redirected to login (HTTP 302)")
    void anonymous_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/manager/analytics/revenue"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "manager@aureliabook.vn", roles = {"MANAGER"})
    @DisplayName("Export revenue excel returns xlsx binary with valid attachment header")
    void exportRevenueExcel_success() throws Exception {
        RevenueFilterRequest filter = RevenueFilterRequest.builder().preset("last7days").build();
        filter.resolveDates();

        RevenueAnalyticsReportDto report = RevenueAnalyticsReportDto.builder()
                .filter(filter)
                .build();

        when(analyticsService.getRevenueReport(any())).thenReturn(report);
        doNothing().when(excelExportService).exportRevenueExcel(any(), any(OutputStream.class));

        mockMvc.perform(get("/manager/analytics/export/revenue")
                        .param("preset", "last7days"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("Bao_Cao_Doanh_Thu_")));
    }

    @Test
    @WithMockUser(username = "manager@aureliabook.vn", roles = {"MANAGER"})
    @DisplayName("Export period stock excel returns xlsx binary with valid attachment header (F04)")
    void exportPeriodStockExcel_success() throws Exception {
        when(analyticsService.getPeriodStockMovementReport(any())).thenReturn(Collections.emptyList());
        when(analyticsService.getStockValuationReport()).thenReturn(Collections.emptyList());
        doNothing().when(excelExportService).exportPeriodStockReport(any(), any(), any(), any(OutputStream.class));

        mockMvc.perform(get("/manager/analytics/export/stock")
                        .param("startDate", "2026-10-01")
                        .param("endDate", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("Bao_Cao_Ton_Kho_Bien_Dong_")));
    }

    @Test
    @WithMockUser(username = "manager@aureliabook.vn", roles = {"MANAGER"})
    @DisplayName("Export stock valuation snapshot excel returns xlsx binary with valid attachment header")
    void exportStockValuationOnlyExcel_success() throws Exception {
        when(analyticsService.getStockValuationReport()).thenReturn(List.of(new StockValuationItemDto()));
        doNothing().when(excelExportService).exportStockValuationExcel(any(), any(OutputStream.class));

        mockMvc.perform(get("/manager/analytics/export/stock-valuation"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("Bao_Cao_Ton_Kho_Dinh_Gia_")));
    }

    @Test
    @WithMockUser(username = "manager@aureliabook.vn", roles = {"MANAGER"})
    @DisplayName("AJAX REST endpoint /api/revenue-data returns JSON report")
    void getRevenueDataApi_success() throws Exception {
        RevenueAnalyticsReportDto report = RevenueAnalyticsReportDto.builder()
                .filter(new RevenueFilterRequest())
                .kpi(RevenueSummaryKpiDto.builder().totalOrders(2).grossRevenue(BigDecimal.valueOf(400000)).build())
                .build();

        when(analyticsService.getRevenueReport(any())).thenReturn(report);

        mockMvc.perform(get("/manager/analytics/api/revenue-data"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.kpi.totalOrders").value(2))
                .andExpect(jsonPath("$.kpi.grossRevenue").value(400000));
    }
}
