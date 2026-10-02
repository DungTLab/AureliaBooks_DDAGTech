package com.ddagtech.aureliabooks.dto.request;

import java.math.BigDecimal;
import com.ddagtech.aureliabooks.entity.Book;

/** FND-02 / UC01-02. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
public record ProductFilterRequest(
        String keyword,
        Long categoryId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Long authorId,
        Long publisherId,
        Long brandId,
        Book.CoverType coverType) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
