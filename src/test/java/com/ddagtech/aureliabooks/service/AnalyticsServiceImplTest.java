package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.*;
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
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UC26, UC27: AnalyticsService Unit Tests")
class AnalyticsServiceImplTest {

    @Mock
    private AnalyticsRepository analyticsRepository;

    @InjectMocks
    private AnalyticsServiceImpl analyticsService;

    @Test
    void getRevenueReport_happyPath() {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 3))
                .preset("custom")
                .build();

        RevenueSummaryKpiDto kpiFromRepo = RevenueSummaryKpiDto.builder()
                .totalOrders(20)
                .grossRevenue(BigDecimal.valueOf(6000000))
                .build();

        when(analyticsRepository.queryRevenueSummaryKpis(any(), any())).thenReturn(kpiFromRepo);
        when(analyticsRepository.queryDailyRevenueTrends(any(), any())).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryTopSellingProducts(any(), anyInt(), any(), any())).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryPaymentMethodBreakdown(any(), any())).thenReturn(new PaymentBreakdownDto());
        when(analyticsRepository.queryDeliveredOrders(any(), any(), anyInt())).thenReturn(Collections.emptyList());

        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);

        assertThat(report.getKpi().getTotalOrders()).isEqualTo(20);
        assertThat(report.getKpi().getAverageOrderValue()).isEqualByComparingTo(BigDecimal.valueOf(300000));
        assertThat(report.getDailyTrends()).hasSize(3); // Zero-filling: 3 days
    }
}
