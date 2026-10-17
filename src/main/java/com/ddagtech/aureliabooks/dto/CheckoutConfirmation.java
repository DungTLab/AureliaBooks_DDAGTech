package com.ddagtech.aureliabooks.dto;

import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.entity.Order;

public record CheckoutConfirmation(
        String checkoutToken,
        Long userId,
        CheckoutView reviewedCheckout,
        Order.PaymentMethod paymentMethod,
        String voucherCode
) {
}
