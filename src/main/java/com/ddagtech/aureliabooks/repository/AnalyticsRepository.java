package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Product;

import java.time.LocalDateTime;
import java.util.List;

public interface AnalyticsRepository {
    RevenueSummaryKpiDto queryRevenueSummaryKpis(LocalDateTime startDateTime, LocalDateTime endDateTime);
    List<DailyRevenueDto> queryDailyRevenueTrends(LocalDateTime startDateTime, LocalDateTime endDateTime);
    PaymentBreakdownDto queryPaymentMethodBreakdown(LocalDateTime startDateTime, LocalDateTime endDateTime);
    List<TopProductSaleDto> queryTopSellingProducts(Product.ProductType productType, int limit, LocalDateTime startDateTime, LocalDateTime endDateTime);
    List<RevenueOrderSummaryDto> queryDeliveredOrders(LocalDateTime startDateTime, LocalDateTime endDateTime, int limit);
    List<StockValuationItemDto> queryStockValuationReport();
}
