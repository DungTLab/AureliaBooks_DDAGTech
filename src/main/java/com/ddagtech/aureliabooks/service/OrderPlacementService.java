package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;

public interface OrderPlacementService {
    CheckoutView getCheckoutView(Long userId, Long shippingAddressId);
    String placeOrder(Long userId, CheckoutRequest request, CheckoutConfirmation confirmation);
}
