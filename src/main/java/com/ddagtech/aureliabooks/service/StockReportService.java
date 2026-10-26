package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.StockMovementFilterRequest;
import com.ddagtech.aureliabooks.dto.analytics.StockReportView;

public interface StockReportService {
    StockReportView view(StockMovementFilterRequest filter, String keyword, String category, String productType);
}
