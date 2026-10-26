package com.ddagtech.aureliabooks.dto.analytics;

import java.math.BigDecimal;
import java.util.List;

/** Period movement and current valuation deliberately use separate totals. */
public record StockReportView(StockMovementFilterRequest filter, String keyword, String category, String productType,
        List<String> categories, List<StockMovementReportItemDto> movements, List<StockValuationItemDto> valuations,
        long openingQuantity, long closingQuantity, BigDecimal closingCost, BigDecimal currentCost) {
}
