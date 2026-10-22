package com.ddagtech.aureliabooks.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueAnalyticsReportDto {

    @Builder.Default
    private RevenueFilterRequest filter = new RevenueFilterRequest();

    @Builder.Default
    private RevenueSummaryKpiDto kpi = new RevenueSummaryKpiDto();

    @Builder.Default
    private List<DailyRevenueDto> dailyTrends = new ArrayList<>();

    @Builder.Default
    private List<TopProductSaleDto> topBooks = new ArrayList<>();

    @Builder.Default
    private List<TopProductSaleDto> topStationeries = new ArrayList<>();

    @Builder.Default
    private PaymentBreakdownDto paymentBreakdown = new PaymentBreakdownDto();

    @Builder.Default
    private List<RevenueOrderSummaryDto> recentDeliveredOrders = new ArrayList<>();

    @Builder.Default
    private List<String> chartDailyLabels = new ArrayList<>();

    @Builder.Default
    private List<BigDecimal> chartDailyRevenues = new ArrayList<>();

    @Builder.Default
    private List<Long> chartDailyOrderCounts = new ArrayList<>();

    @Builder.Default
    private List<String> chartPaymentLabels = new ArrayList<>();

    @Builder.Default
    private List<BigDecimal> chartPaymentValues = new ArrayList<>();

    @Builder.Default
    private List<Long> chartPaymentCounts = new ArrayList<>();

    public boolean isHasData() {
        return kpi != null && kpi.getTotalOrders() > 0;
    }

    public boolean isHasTopBooks() {
        return topBooks != null && !topBooks.isEmpty();
    }

    public boolean isHasTopStationeries() {
        return topStationeries != null && !topStationeries.isEmpty();
    }

    public boolean isHasDeliveredOrders() {
        return recentDeliveredOrders != null && !recentDeliveredOrders.isEmpty();
    }
}
