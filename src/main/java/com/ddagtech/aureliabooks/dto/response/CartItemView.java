package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;

public record CartItemView(
        Long itemId,
        Long productId,
        String title,
        String mainImageUrl,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal lineTotal,
        Integer availableStock,
        boolean availableForSale,
        String warningMessage
) {}
