package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.entity.Product;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload to quickly create a product on-the-fly during goods receipt drafting (UC22).
 */
public record QuickCreateProductRequest(
        @NotBlank(message = "Tên sản phẩm không được để trống")
        @Size(max = 255, message = "Tên sản phẩm tối đa 255 ký tự")
        String title,

        @NotNull(message = "Loại sản phẩm không được để trống")
        Product.ProductType productType,

        Boolean isTextbook,

        Long categoryId,

        String barcode,

        @NotNull(message = "Giá bán không được để trống")
        @DecimalMin(value = "0.00", message = "Giá bán không được âm")
        BigDecimal price,

        @DecimalMin(value = "0.00", message = "Giá nhập không được âm")
        BigDecimal originalCost,

        Integer initialQuantity
) {
    public boolean isTextbookSafe() {
        return Boolean.TRUE.equals(isTextbook);
    }
}
