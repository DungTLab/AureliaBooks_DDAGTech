package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.entity.Order;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CheckoutRequest {
    @NotNull(message = "Vui lòng chọn địa chỉ giao hàng")
    @Positive(message = "Địa chỉ giao hàng không hợp lệ")
    private Long shippingAddressId;

    @NotNull(message = "Vui lòng chọn phương thức thanh toán")
    private Order.PaymentMethod paymentMethod;

    @Size(max = 30, message = "Mã giảm giá tối đa 30 ký tự")
    private String voucherCode;

    @NotBlank(message = "Phiên xác nhận đặt hàng không hợp lệ")
    @Pattern(
            regexp = "[a-f0-9]{32}",
            message = "Mã xác nhận đặt hàng không hợp lệ"
    )
    private String checkoutToken;
}
