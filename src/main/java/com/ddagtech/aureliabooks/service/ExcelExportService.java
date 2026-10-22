package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.RevenueAnalyticsReportDto;
import com.ddagtech.aureliabooks.dto.analytics.StockValuationItemDto;

import java.io.OutputStream;
import java.util.List;

public interface ExcelExportService {
    void exportRevenueExcel(RevenueAnalyticsReportDto reportDto, OutputStream outputStream);
    void exportStockValuationExcel(List<StockValuationItemDto> stockItems, OutputStream outputStream);
}
