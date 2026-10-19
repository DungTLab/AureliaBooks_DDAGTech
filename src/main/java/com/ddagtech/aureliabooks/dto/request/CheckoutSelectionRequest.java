package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.entity.Order;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** The editable step precedes server issuance of the final checkout token. */
public record CheckoutSelectionRequest(
        @NotNull(message = "Vui lòng chọn địa chỉ giao hàng")
        @Positive(message = "Địa chỉ giao hàng không hợp lệ") Long shippingAddressId,
        @NotNull(message = "Vui lòng chọn phương thức thanh toán") Order.PaymentMethod paymentMethod,
        @Size(max = 30, message = "Mã giảm giá tối đa 30 ký tự") String voucherCode
) {}
