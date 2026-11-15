package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.RevenueAnalyticsReportDto;
import com.ddagtech.aureliabooks.dto.analytics.RevenueFilterRequest;
import com.ddagtech.aureliabooks.dto.analytics.StockMovementFilterRequest;
import com.ddagtech.aureliabooks.dto.analytics.StockMovementReportItemDto;
import com.ddagtech.aureliabooks.dto.analytics.StockValuationItemDto;

import java.util.List;

/**
 * Service orchestrating revenue analytics reports, KPI calculations, and stock movement aggregations.
 */
public interface AnalyticsService {
    RevenueAnalyticsReportDto getRevenueReport(RevenueFilterRequest filterRequest);
    List<StockValuationItemDto> getStockValuationReport();
    List<StockMovementReportItemDto> getPeriodStockMovementReport(StockMovementFilterRequest filterRequest);
}
