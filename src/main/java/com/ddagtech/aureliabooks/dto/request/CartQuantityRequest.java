package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CartQuantityRequest {
    public static final int MAX_QUANTITY = 10;
    public static final int MAX_DISTINCT_PRODUCTS = 20;

    @NotNull(message = "Vui lòng nhập số lượng")
    @Min(value=1, message = "Số lượng ít nhất là 1")
    @Max(value = MAX_QUANTITY, message = "Số lượng không được vượt quá 10")
    private Integer quantity;
}
