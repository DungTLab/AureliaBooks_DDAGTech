package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Product;

import java.time.LocalDateTime;
import java.util.List;

public interface AnalyticsRepository {
    RevenueSummaryKpiDto queryRevenueSummaryKpis(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime);
    List<DailyRevenueDto> queryDailyRevenueTrends(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime);
    List<DailyRevenueDto> queryMonthlyRevenueTrends(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime);
    PaymentBreakdownDto queryPaymentMethodBreakdown(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime);
    List<TopProductSaleDto> queryTopSellingProducts(Product.ProductType productType, int limit, LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime);
    List<RevenueOrderSummaryDto> queryDeliveredOrders(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime, int limit);
    List<RevenueOrderSummaryDto> queryAllDeliveredOrders(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime);
    List<StockValuationItemDto> queryStockValuationReport();
    List<StockMovementReportItemDto> queryPeriodStockMovementReport(LocalDateTime fromInclusive, LocalDateTime toExclusive);
}
