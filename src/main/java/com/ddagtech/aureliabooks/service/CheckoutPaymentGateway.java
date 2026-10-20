package com.ddagtech.aureliabooks.service;

/**
 * UC11.1 integration point: register an implementation only when VNPay is configured. The adapter
 * loads the owner's committed order, checks eligibility, and signs a URL using the stored amount.
 * Starting payment must never deduct stock or mark payment successful.
 */
public interface CheckoutPaymentGateway {
    /** Report whether the controller can start payment at this time. */
    boolean isAvailable();

    /** Return a signed HTTPS URL for an existing order; the controller calls this after order creation commits. */
    String startPayment(Long authenticatedUserId, String orderCode);
}
