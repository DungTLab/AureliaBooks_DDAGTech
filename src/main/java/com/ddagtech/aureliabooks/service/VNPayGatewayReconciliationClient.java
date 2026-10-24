package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.Order;

/**
 * Port interface for reconciling unverified VNPay transactions before cancelling
 * expired PENDING_PAYMENT orders (CRON-01 / BR-04-04).
 *
 * Timeout or closing the browser does not automatically prove payment failure;
 * the system verifies gateway settlement state before cancelling and restoring stock.
 */
public interface VNPayGatewayReconciliationClient {

    /**
     * Queries or reconciles the payment settlement outcome of an expired PENDING_PAYMENT order.
     *
     * @param order candidate expired order
     * @return verified reconciliation outcome
     */
    ReconciliationResult reconcileExpiredOrder(Order order);

    /**
     * Immutable reconciliation outcome from the payment gateway.
     */
    record ReconciliationResult(
            ReconciliationOutcome outcome,
            String payDate,
            String bankCode,
            String diagnosticMessage
    ) {
        public static ReconciliationResult unpaidOrFailed(String diagnosticMessage) {
            return new ReconciliationResult(ReconciliationOutcome.VERIFIED_UNPAID_OR_FAILED, null, null, diagnosticMessage);
        }

        public static ReconciliationResult paid(String payDate, String bankCode, String diagnosticMessage) {
            return new ReconciliationResult(ReconciliationOutcome.VERIFIED_PAID, payDate, bankCode, diagnosticMessage);
        }

        public static ReconciliationResult indeterminate(String diagnosticMessage) {
            return new ReconciliationResult(ReconciliationOutcome.INDETERMINATE, null, null, diagnosticMessage);
        }
    }

    enum ReconciliationOutcome {
        VERIFIED_UNPAID_OR_FAILED,
        VERIFIED_PAID,
        INDETERMINATE
    }
}
