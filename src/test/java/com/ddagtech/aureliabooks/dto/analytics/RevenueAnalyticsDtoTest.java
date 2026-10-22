package com.ddagtech.aureliabooks.dto.analytics;

import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UC26, UC27: Revenue Analytics & Excel Export DTO Tests")
class RevenueAnalyticsDtoTest {

    @Test
    void filterRequest_defaultDates() {
        RevenueFilterRequest filter = new RevenueFilterRequest();
        filter.resolveDates();

        LocalDate now = LocalDate.now();
        assertThat(filter.getStartDate()).isNotNull();
        assertThat(filter.getEndDate()).isEqualTo(now);
        assertThat(filter.isValidDateRange()).isTrue();
    }

    @Test
    void filterRequest_presets() {
        LocalDate today = LocalDate.now();

        RevenueFilterRequest todayReq = RevenueFilterRequest.builder().preset("today").build();
        todayReq.resolveDates();
        assertThat(todayReq.getStartDate()).isEqualTo(today);
        assertThat(todayReq.getEndDate()).isEqualTo(today);

        RevenueFilterRequest last7Req = RevenueFilterRequest.builder().preset("last7days").build();
        last7Req.resolveDates();
        assertThat(last7Req.getStartDate()).isEqualTo(today.minusDays(6));
        assertThat(last7Req.getEndDate()).isEqualTo(today);
    }

    @Test
    void kpiDto_formatting() {
        RevenueSummaryKpiDto kpi = RevenueSummaryKpiDto.builder()
                .totalOrders(10)
                .grossRevenue(BigDecimal.valueOf(2500000))
                .averageOrderValue(BigDecimal.valueOf(250000))
                .totalShippingFee(BigDecimal.valueOf(60000))
                .build();

        assertThat(kpi.getFormattedGrossRevenue()).contains("2");
        assertThat(kpi.getFormattedGrossRevenue()).endsWith("₫");
    }

    @Test
    void paymentBreakdown_electronicTarget() {
        PaymentBreakdownDto low = PaymentBreakdownDto.builder()
                .codRevenue(BigDecimal.valueOf(700000))
                .vnpayRevenue(BigDecimal.valueOf(300000))
                .electronicSharePercent(30.0)
                .build();
        assertThat(low.isMeetingElectronicTarget()).isFalse();

        PaymentBreakdownDto high = PaymentBreakdownDto.builder()
                .codRevenue(BigDecimal.valueOf(400000))
                .vnpayRevenue(BigDecimal.valueOf(600000))
                .electronicSharePercent(60.0)
                .build();
        assertThat(high.isMeetingElectronicTarget()).isTrue();
    }
}
