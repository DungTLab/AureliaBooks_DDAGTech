package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;

public record ProductAutoCompleteResponse(
        Long id,
        String title,
        BigDecimal price,
        String imageUrl,
        String barcode
) {
}
