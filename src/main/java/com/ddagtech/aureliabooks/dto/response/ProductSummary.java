package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Product;

import java.math.BigDecimal;

public record ProductSummary(Long id, String title, BigDecimal price, Integer stockQuantity, String mainImageUrl,
                             Product.ProductType productType,
                             String seriesName,
                             Integer volumeNumber) {
    public ProductSummary(Long id, String title, BigDecimal price, Integer stockQuantity, String mainImageUrl) {
        this(id, title, price, stockQuantity, mainImageUrl, Product.ProductType.BOOK, null, null);
    }
}
