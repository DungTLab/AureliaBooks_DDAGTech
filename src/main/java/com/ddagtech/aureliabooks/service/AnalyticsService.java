package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.*;

import java.util.List;

public interface AnalyticsService {
    RevenueAnalyticsReportDto getRevenueReport(RevenueFilterRequest filterRequest);
    List<StockValuationItemDto> getStockValuationReport();
    List<StockMovementReportItemDto> getPeriodStockMovementReport(StockMovementFilterRequest filterRequest);
}
