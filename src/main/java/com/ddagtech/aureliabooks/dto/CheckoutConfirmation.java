package com.ddagtech.aureliabooks.dto;

import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.entity.Order;

/** Server-owned confirmation linking the token, customer, reviewed details, and payment method; never bind it from a form. */
public record CheckoutConfirmation(
        String checkoutToken,
        Long userId,
        CheckoutView reviewedCheckout,
        Order.PaymentMethod paymentMethod,
        String voucherCode,
        String shippingVoucherCode
) {
    public CheckoutConfirmation(String token, Long owner, CheckoutView view, Order.PaymentMethod method, String code) {
        this(token, owner, view, method, code, null);
    }
}
