package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Product;

import java.math.BigDecimal;

/**
 * UC22/23: Detailed line item representation for receipt view and modal inspection.
 */
public record GoodsReceiptItemDetailDto(
        Long id,
        Long productId,
        String productTitle,
        String productBarcode,
        String categoryName,
        Product.ProductType productType,
        Boolean isTextbook,
        Integer receivedQuantity,
        BigDecimal unitCost,
        BigDecimal subtotal
) {
    public GoodsReceiptItemDetailDto(
            Long id,
            Long productId,
            String productTitle,
            String productBarcode,
            String categoryName,
            Product.ProductType productType,
            Boolean isTextbook,
            Integer receivedQuantity,
            BigDecimal unitCost
    ) {
        this(
                id,
                productId,
                productTitle,
                productBarcode,
                categoryName,
                productType,
                isTextbook,
                receivedQuantity,
                unitCost,
                unitCost != null && receivedQuantity != null
                        ? unitCost.multiply(BigDecimal.valueOf(receivedQuantity))
                        : BigDecimal.ZERO
        );
    }
}
