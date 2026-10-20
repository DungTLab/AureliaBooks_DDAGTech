package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** UC22/23: received_quantity and unit_cost. Owner: Nguyễn Trần Đức Anh. */
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
