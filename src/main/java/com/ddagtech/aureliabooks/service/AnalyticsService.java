package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.RevenueAnalyticsReportDto;
import com.ddagtech.aureliabooks.dto.analytics.RevenueFilterRequest;
import com.ddagtech.aureliabooks.dto.analytics.StockValuationItemDto;

import java.util.List;

public interface AnalyticsService {
    RevenueAnalyticsReportDto getRevenueReport(RevenueFilterRequest filterRequest);
    List<StockValuationItemDto> getStockValuationReport();
}
