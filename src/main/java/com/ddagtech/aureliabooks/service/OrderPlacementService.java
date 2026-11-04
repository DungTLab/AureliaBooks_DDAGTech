package com.ddagtech.aureliabooks.service;
import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.dto.response.OrderConfirmationView;

/** UC11 contract for checkout previews, atomic order placement, and confirmations owned by the authenticated customer. */
public interface OrderPlacementService {
    /** Select the requested or default address and return preview totals without reserving stock. */
    CheckoutView getCheckoutView(Long userId, Long shippingAddressId);


    /** Apply a product voucher to server-calculated totals without consuming quota. */
    CheckoutView getCheckoutView(Long userId, Long shippingAddressId, String voucherCode);

    /** Apply at most one voucher per scope; neither preview reserves quota. */
    CheckoutView getCheckoutView(Long userId, Long shippingAddressId, String voucherCode, String shippingVoucherCode);

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
