package com.ddagtech.aureliabooks.dto.request;

import java.math.BigDecimal;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Product;

/**
 * Filter request parameters for catalog browsing and search (FND-02 / UC01-02).
 */
public record ProductFilterRequest(
        String keyword,
        Long categoryId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Long authorId,
        Long publisherId,
        Long brandId,
        Book.CoverType coverType,
        Product.ProductType productType,
        Boolean isTextbook) {

    public ProductFilterRequest(
            String keyword,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Long authorId,
            Long publisherId,
            Long brandId,
            Book.CoverType coverType) {
        this(keyword, categoryId, minPrice, maxPrice, authorId, publisherId, brandId, coverType, null, null);
    }
}
