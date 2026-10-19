package com.ddagtech.aureliabooks.service;

/**
 * Optional UC11.1 handoff. Register an implementation only when VNPay is configured.
 * Implementations load the owner's committed order, verify its eligibility, use the stored
 * amount and sign the gateway URL. They must never deduct stock or mark payment successful.
 */
public interface CheckoutPaymentGateway {
    boolean isAvailable();

    String startPayment(Long authenticatedUserId, String orderCode);
}
