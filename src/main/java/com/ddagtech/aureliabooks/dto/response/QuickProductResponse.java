package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;

/**
 * Response payload returned after quick-creating a product on-the-fly.
 */
public record QuickProductResponse(
        Long id,
        String title,
        String barcode,
        BigDecimal price,
        BigDecimal originalCost,
        String productType,
        Boolean isTextbook,
        Integer initialQuantity
) {
}
