package com.ddagtech.aureliabooks.dto;

import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.entity.Order;

/** Bản xác nhận do server lưu: gắn token, khách, nội dung đã xem và phương thức thanh toán; không bind từ form. */
public record CheckoutConfirmation(
        String checkoutToken,
        Long userId,
        CheckoutView reviewedCheckout,
        Order.PaymentMethod paymentMethod,
        String voucherCode
) {
}
