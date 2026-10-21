package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Product;

import java.math.BigDecimal;

/**
 * UC21: Product inventory status representation with threshold badges.
 * Rules:
 *  - stock == 0: OUT_OF_STOCK (badge: danger / đỏ)
 *  - stock in [1..10]: LOW_STOCK (badge: warning / vàng)
 *  - stock > 10: SAFE_STOCK (badge: success / xanh lá)
 */
public record ProductStockAlertDto(
        Long id,
        String barcode,
        String title,
        Long categoryId,
        String categoryName,
        Product.ProductType productType,
        BigDecimal price,
        BigDecimal originalCost,
        Integer stockQuantity,
        String alertStatus,
        String alertBadgeClass,
        String alertLabel,
        Boolean isTextbook
) {
    public static ProductStockAlertDto of(
            Product product,
            String categoryName,
            Boolean isTextbook
    ) {
        int qty = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        String status;
        String badgeClass;
        String label;

        if (qty <= 0) {
            status = "OUT_OF_STOCK";
            badgeClass = "bg-rose-100 text-rose-800 border-rose-300";
            label = "Hết hàng";
        } else if (qty <= 10) {
            status = "LOW_STOCK";
            badgeClass = "bg-amber-100 text-amber-800 border-amber-300";
            label = "Tồn kho thấp";
        } else {
            status = "SAFE_STOCK";
            badgeClass = "bg-emerald-100 text-emerald-800 border-emerald-300";
            label = "An toàn";
        }

        return new ProductStockAlertDto(
                product.getId(),
                product.getBarcode(),
                product.getTitle(),
                product.getCategory() != null ? product.getCategory().getId() : null,
                categoryName != null ? categoryName : (product.getCategory() != null ? product.getCategory().getName() : "-"),
                product.getProductType(),
                product.getPrice(),
                product.getOriginalCost(),
                qty,
                status,
                badgeClass,
                label,
                isTextbook
        );
    }
}
