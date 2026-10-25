package com.ddagtech.aureliabooks.service;
import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.dto.response.OrderConfirmationView;

/** UC11 contract for checkout previews, atomic order placement, and confirmations owned by the authenticated customer. */
public interface OrderPlacementService {
    /** Select the requested or default address and return preview totals without reserving stock. */
    CheckoutView getCheckoutView(Long userId, Long shippingAddressId);


    /** Apply one voucher to server-calculated cart and delivery totals without consuming quota. */
    CheckoutView getCheckoutView(Long userId, Long shippingAddressId, String voucherCode);

    /** Return snapshots only for the specified owner; missing and foreign orders both produce a not-found error. */
    OrderConfirmationView getOrderConfirmation(Long userId, String orderCode);

    /**
     * Create a COD/VNPay order and deduct stock within one transaction; replaying a committed token
     * returns the existing order. Resolve userId from the authenticated account and load
     * confirmation from the server session. The token also serves as the unique order code; price,
     * address, or quantity changes require a new customer review.
     */
    String placeOrder(Long userId, CheckoutRequest request, CheckoutConfirmation confirmation);
}
