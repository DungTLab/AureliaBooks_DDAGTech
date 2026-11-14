package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.analytics.DailyRevenueDto;
import com.ddagtech.aureliabooks.dto.analytics.PaymentBreakdownDto;
import com.ddagtech.aureliabooks.dto.analytics.RevenueOrderSummaryDto;
import com.ddagtech.aureliabooks.dto.analytics.RevenueSummaryKpiDto;
import com.ddagtech.aureliabooks.dto.analytics.StockMovementReportItemDto;
import com.ddagtech.aureliabooks.dto.analytics.StockValuationItemDto;
import com.ddagtech.aureliabooks.dto.analytics.TopProductSaleDto;
import com.ddagtech.aureliabooks.entity.Product;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository interface for executive analytics queries, revenue trends, and inventory valuation (UC26, UC27).
 */
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
