package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.VNPayIpnResponse;
import com.ddagtech.aureliabooks.dto.response.VNPayPaymentResultView;

import java.util.Map;

/**
 * Service contract for VNPay Sandbox payment orchestration (UC11.1).
 *
 * V2.4 Invariants enforced:
 * - Available inventory is already deducted atomically at order creation (UC11) with stock_deducted_at stamped.
 * - Verified IPN payment success transitions payment_status = PAID and order_status = PENDING_CONFIRMATION
 *   without deducting inventory a second time (BR-04-02).
 * - Verified IPN terminal payment failure or cancellation transitions order_status = CANCELLED, keeps payment_status = UNPAID,
 *   restores deducted stock exactly once (stock_restored_at, stock_restore_reason = CANCELLED), and releases vouchers.
 * - Indeterminate or incomplete gateway statuses keep the order in PENDING_PAYMENT for reconciliation.
 * - Browser Return URL (SCR-ST-13) is strictly presentation-only and never mutates database state.
 */
public interface VNPayService {

    /**
     * Generates a cryptographically signed VNPay Sandbox payment redirect URL with a fixed 15-minute TTL
     * anchored to the order creation timestamp, verifying that the authenticated customer owns the order.
     *
     * @param orderId  ID of the order to pay
     * @param clientIp customer's IPv4 address
     * @return full HTTPS redirect URL to the VNPay Sandbox gateway
     */
    String createPaymentUrl(Long orderId, String clientIp);

    /**
     * Generates a cryptographically signed VNPay Sandbox payment redirect URL for an order owned by
     * the specified authenticated customer ID.
     *
     * @param authenticatedUserId authenticated customer's user ID
     * @param orderId             ID of the order to pay
     * @param clientIp            customer's IPv4 address
     * @return full HTTPS redirect URL to the VNPay Sandbox gateway
     */
    default String createPaymentUrl(Long authenticatedUserId, Long orderId, String clientIp) {
        return createPaymentUrl(orderId, clientIp);
    }

    /**
     * Processes the asynchronous server-to-server VNPay IPN webhook callback.
     * Verifies HMAC-SHA512 signature, locks the order pessimistically, validates amount and idempotency,
     * classifies terminal vs indeterminate outcomes, and applies V2.4 state transitions without duplicate stock deduction.
     *
     * @param params   incoming query/form parameters from VNPay
     * @param sourceIp IP address of the caller
     * @return standard VNPay JSON acknowledgment (RspCode, Message)
     */
    VNPayIpnResponse processIpnWebhook(Map<String, String> params, String sourceIp);

    /**
     * Evaluates the synchronous browser Return URL query parameters to build a read-only view model
     * for SCR-ST-13 (Payment & Order Result). Never mutates order, payment, or inventory state.
     *
     * @param params query parameters forwarded by the customer's browser
     * @return read-only view model for SCR-ST-13
     */
    VNPayPaymentResultView evaluateBrowserReturn(Map<String, String> params);
}
