package com.ddagtech.aureliabooks.service;
import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.dto.response.OrderConfirmationView;

public interface OrderPlacementService {
    CheckoutView getCheckoutView(Long userId, Long shippingAddressId);


    OrderConfirmationView getOrderConfirmation(Long userId, String orderCode);

    /**
     * Creates and posts a COD/VNPay order atomically, or returns the committed order for a replay.
     * userId must come from the authenticated identity; confirmation must be loaded from server
     * session state, never bound directly from a request body/form. The server-issued checkout
     * token is reused as the unique order code. A changed review requires explicit reconfirmation.
     */
    String placeOrder(Long userId, CheckoutRequest request, CheckoutConfirmation confirmation);
}
