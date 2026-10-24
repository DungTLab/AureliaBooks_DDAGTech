package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.VNPayIpnResponse;
import com.ddagtech.aureliabooks.dto.response.VNPayPaymentResultView;

import java.util.Map;

/**
 * Service contract for VNPay Sandbox payment orchestration (UC11.1).
 *
 * V2.3 Invariants enforced:
 * - Available inventory is already deducted atomically at order creation (UC11) with stock_deducted_at stamped.
 * - Verified IPN payment success transitions payment_status = PAID and order_status = PENDING_CONFIRMATION
 *   without deducting inventory a second time (BR-04-02).
 * - Verified IPN payment failure or cancellation transitions order_status = CANCELLED, keeps payment_status = UNPAID,
 *   restores deducted stock exactly once (stock_restored_at, stock_restore_reason = CANCELLED), and releases vouchers.
 * - Browser Return URL (SCR-ST-13) is strictly presentation-only and never mutates database state.
 */
public interface VNPayService {

    /**
     * Generates a cryptographically signed VNPay Sandbox payment redirect URL with a 15-minute TTL
     * for an existing PENDING_PAYMENT order whose inventory was already deducted at checkout (UC11).
     *
     * @param orderId  ID of the order to pay
     * @param clientIp customer's IPv4/IPv6 address
     * @return full HTTPS redirect URL to the VNPay Sandbox gateway
     */
    String createPaymentUrl(Long orderId, String clientIp);

    /**
     * Processes the asynchronous server-to-server VNPay IPN webhook callback.
     * Verifies HMAC-SHA512 signature, locks the order pessimistically, validates amount and idempotency,
     * and applies V2.3 state transitions without duplicate stock deduction.
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
