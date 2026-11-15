package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.StockMovementFilterRequest;
import com.ddagtech.aureliabooks.dto.analytics.StockReportView;

/**
 * Service providing combined period movements and current stock valuation view models.
 */
public interface StockReportService {
    StockReportView view(StockMovementFilterRequest filter, String keyword, String category, String productType);
}
