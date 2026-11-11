package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request form DTO for an individual line item in a goods receipt (UC21, UC22, UC23).
 *
 * @param productId persistent identifier of the inbound product
 * @param receivedQuantity inbound quantity received
 * @param unitCost purchase unit cost in VND
 */
public record GoodsReceiptItemForm(
        @NotNull(message = "Vui lòng chọn sản phẩm.")
        Long productId,

        @NotNull(message = "Số lượng nhập không được để trống.")
        @Min(value = 1, message = "Số lượng nhập phải lớn hơn 0.")
        Integer receivedQuantity,

        @NotNull(message = "Đơn giá nhập không được để trống.")
        @DecimalMin(value = "0.00", message = "Đơn giá nhập không được âm.")
        BigDecimal unitCost
) {}
