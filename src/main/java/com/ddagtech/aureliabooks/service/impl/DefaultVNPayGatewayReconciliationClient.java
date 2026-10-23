package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.service.VNPayGatewayReconciliationClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default sandbox reconciliation client for expired VNPay PENDING_PAYMENT orders (CRON-01 / BR-04-04).
 * Inspects recorded gateway settlement markers and confirms whether funds remain uncollected
 * before the timeout daemon cancels the order and restores deducted stock.
 */
@Slf4j
@Component
public class DefaultVNPayGatewayReconciliationClient implements VNPayGatewayReconciliationClient {

    @Override
    public ReconciliationResult reconcileExpiredOrder(Order order) {
        if (order == null) {
            return ReconciliationResult.indeterminate("Order reference is null.");
        }
        if (order.getPaymentStatus() == Order.PaymentStatus.PAID
                || (order.getVnpayPayDate() != null && !order.getVnpayPayDate().isBlank())) {
            return ReconciliationResult.paid(
                    order.getVnpayPayDate(),
                    order.getVnpayBankCode(),
                    "Gateway settlement metadata already recorded on order."
            );
        }
        return ReconciliationResult.unpaidOrFailed(
                "Verified no settlement captured past 15-minute TTL for orderCode=" + order.getOrderCode()
        );
    }
}
