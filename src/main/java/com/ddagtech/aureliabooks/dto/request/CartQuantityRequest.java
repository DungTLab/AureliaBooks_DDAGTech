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

    @NotNull(message = "Vui lòng nhập số lượng")
    @Min(value=1, message = "Số lượng ít nhất là 1")
    @Max(value = 99, message = "Số lượng không được vượt quá 99")
    Integer quantity;
}
