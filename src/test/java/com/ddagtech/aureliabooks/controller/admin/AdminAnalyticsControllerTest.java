package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.analytics.RevenueAnalyticsReportDto;
import com.ddagtech.aureliabooks.dto.analytics.RevenueFilterRequest;
import com.ddagtech.aureliabooks.dto.analytics.RevenueSummaryKpiDto;
import com.ddagtech.aureliabooks.service.AnalyticsService;
import com.ddagtech.aureliabooks.service.ExcelExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.math.BigDecimal;

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
    @WithMockUser(username = "staff@aureliabook.vn", roles = {"SALE_STAFF"})
    void saleStaff_isForbidden() throws Exception {
        mockMvc.perform(get("/manager/analytics/revenue"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "manager@aureliabook.vn", roles = {"MANAGER"})
    void exportRevenueExcel_success() throws Exception {
        when(analyticsService.getRevenueReport(any())).thenReturn(new RevenueAnalyticsReportDto());
        doNothing().when(excelExportService).exportRevenueExcel(any(), any(OutputStream.class));

        mockMvc.perform(get("/manager/analytics/export/revenue"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }
}
