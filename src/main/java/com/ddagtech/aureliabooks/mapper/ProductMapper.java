package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Product;

public final class ProductMapper {
    private ProductMapper() {
    }

    public static ProductSummary toSummary(Product product, Book book) {
        boolean isBook = product.getProductType() == Product.ProductType.BOOK && book != null;
        return new ProductSummary(product.getId(), product.getTitle(), product.getPrice(),
                product.getStockQuantity(), product.getMainImageUrl(), product.getProductType(),
                isBook ? book.getSeriesName() : null, isBook ? book.getVolumeNumber() : null);
    }
}
