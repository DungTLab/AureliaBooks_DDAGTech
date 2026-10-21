package com.ddagtech.aureliabooks.dto.response;

/**
 * UC21: Aggregated inventory metrics reflecting real-time stock levels.
 * Exact thresholds: 0 (Out of stock), 1-10 (Low stock), >10 (Safe stock).
 */
public record StockMetricSummary(
        long totalProducts,
        long outOfStockCount,
        long lowStockCount,
        long safeStockCount
) {}
