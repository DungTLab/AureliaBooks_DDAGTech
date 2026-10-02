package com.ddagtech.aureliabooks.dto.request;

import java.math.BigDecimal;

/** UC16. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
public record StationeryForm(
        String barcode,
        String title,
        Long categoryId,
        BigDecimal price,
        BigDecimal originalCost,
        Integer weightGrams,
        String mainImageUrl,
        String tags,
        String description,
        Long brandId,
        String material,
        String color,
        Integer warrantyMonths) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
