package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Product;

import java.math.BigDecimal;
import java.util.List;

/**
 * Detail response DTO for product detail view (UC03 / SCR-C03).
 * Encapsulates common attributes and type-specific attributes for Books and Stationery.
 */
public record ProductDetailResponse(
        Long id,
        String barcode,
        String title,
        BigDecimal price,
        BigDecimal originalCost,
        Integer stockQuantity,
        String mainImageUrl,
        String description,
        Integer weightGrams,
        Product.ProductType productType,
        Long categoryId,
        String categoryName,
        // Book specific fields
        String isbn,
        Long publisherId,
        String publisherName,
        List<String> authorNames,
        String seriesName,
        Integer volumeNumber,
        Boolean isTextbook,
        Integer publicationYear,
        String edition,
        Integer pageCount,
        Book.CoverType coverType,
        String language,
        // Stationery specific fields
        Long brandId,
        String brandName,
        String material,
        String color,
        Integer warrantyMonths
) {
}
