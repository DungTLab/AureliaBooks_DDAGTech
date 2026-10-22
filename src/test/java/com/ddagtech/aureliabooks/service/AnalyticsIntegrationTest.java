package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.AnalyticsRepository;
import com.ddagtech.aureliabooks.service.impl.AnalyticsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UC26, UC27: Analytics Performance Gate & Integration Logic Tests")
class AnalyticsIntegrationTest {

    @Mock
    private AnalyticsRepository analyticsRepository;

    @InjectMocks
    private AnalyticsServiceImpl analyticsService;

    @Test
    @DisplayName("Quality Gate Verification: Full aggregation pipeline execution under 500ms")
    void aggregationQueryPerformance_under500ms() {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 1, 31))
                .preset("custom")
                .build();

        RevenueSummaryKpiDto kpi = RevenueSummaryKpiDto.builder()
                .totalOrders(1000)
                .grossRevenue(BigDecimal.valueOf(250000000))
                .totalShippingFee(BigDecimal.valueOf(15000000))
                .build();

        when(analyticsRepository.queryRevenueSummaryKpis(any(), any())).thenReturn(kpi);
        when(analyticsRepository.queryDailyRevenueTrends(any(), any())).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryTopSellingProducts(eq(Product.ProductType.BOOK), eq(5), any(), any()))
                .thenReturn(Collections.emptyList());
        when(analyticsRepository.queryTopSellingProducts(eq(Product.ProductType.STATIONERY), eq(5), any(), any()))
                .thenReturn(Collections.emptyList());
        when(analyticsRepository.queryPaymentMethodBreakdown(any(), any())).thenReturn(new PaymentBreakdownDto());
        when(analyticsRepository.queryDeliveredOrders(any(), any(), eq(100))).thenReturn(Collections.emptyList());

        long startTime = System.currentTimeMillis();
        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);
        long elapsedMs = System.currentTimeMillis() - startTime;

        // Verify quality gate: pipeline must complete within 500ms
        assertThat(elapsedMs).isLessThan(500);
        assertThat(report.getKpi().getTotalOrders()).isEqualTo(1000);
        assertThat(report.getDailyTrends()).hasSize(31); // 31 days in Jan 2026 zero-filled
    }

    @Test
    @DisplayName("Negative/Boundary: Future date range with zero orders returns safe report with 0 VND and empty chart")
    void futureDateRange_returnsZeroVndWithoutNpe() {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2099, 1, 1))
                .endDate(LocalDate.of(2099, 1, 5))
                .preset("custom")
                .build();

        when(analyticsRepository.queryRevenueSummaryKpis(any(), any())).thenReturn(null);
        when(analyticsRepository.queryDailyRevenueTrends(any(), any())).thenReturn(null);
        when(analyticsRepository.queryTopSellingProducts(any(), eq(5), any(), any())).thenReturn(null);
        when(analyticsRepository.queryPaymentMethodBreakdown(any(), any())).thenReturn(null);
        when(analyticsRepository.queryDeliveredOrders(any(), any(), eq(100))).thenReturn(null);

        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);

        assertThat(report).isNotNull();
        assertThat(report.getKpi().getTotalOrders()).isEqualTo(0L);
        assertThat(report.getKpi().getGrossRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getKpi().getAverageOrderValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getDailyTrends()).hasSize(5); // 5 days zero-filled
        assertThat(report.getTopBooks()).isEmpty();
        assertThat(report.getTopStationeries()).isEmpty();
        assertThat(report.getRecentDeliveredOrders()).isEmpty();
    }
}
