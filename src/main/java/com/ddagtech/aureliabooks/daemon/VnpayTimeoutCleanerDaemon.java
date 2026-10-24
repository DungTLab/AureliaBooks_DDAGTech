package com.ddagtech.aureliabooks.daemon;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.service.OrderVoucherReleasePort;
import com.ddagtech.aureliabooks.service.StockMutationService;
import com.ddagtech.aureliabooks.service.VNPayGatewayReconciliationClient;
import com.ddagtech.aureliabooks.service.VNPayGatewayReconciliationClient.ReconciliationOutcome;
import com.ddagtech.aureliabooks.service.VNPayGatewayReconciliationClient.ReconciliationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRON-01: VNPay Timeout Cleaner & Reconciliation Daemon (BR-04-04 / C07).
 *
 * Scans PENDING_PAYMENT orders that have exceeded the 15-minute TTL window.
 * Per V2.3 Invariant BR-04-04, expiration of the payment window or browser closure does NOT
 * automatically prove payment failure; the daemon reconciles unverified transactions before
 * cancelling the order, restoring the inventory deducted at order creation (UC11) exactly once,
 * and releasing any redeemed voucher quota.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VnpayTimeoutCleanerDaemon {

    private final OrderRepository orderRepository;
    private final VNPayConfig vnPayConfig;
    private final VNPayGatewayReconciliationClient reconciliationClient;
    private final StockMutationService stockMutationService;
    private final OrderVoucherReleasePort orderVoucherReleasePort;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Executes every 60 seconds to reconcile and clean up expired PENDING_PAYMENT orders.
     *
     * @return number of expired orders cancelled and restocked during this tick
     */
    @Scheduled(fixedDelayString = "${app.cron.vnpay-timeout-ms:60000}")
    @Transactional(rollbackFor = Exception.class)
    public int cleanExpiredPendingPaymentOrders() {
        int expireMinutes = vnPayConfig.getExpireMinutes() > 0 ? vnPayConfig.getExpireMinutes() : 15;
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(expireMinutes);

        List<Order> expiredCandidates = orderRepository.findExpiredPendingPaymentOrders(cutoffTime);
        if (expiredCandidates.isEmpty()) {
            return 0;
        }

        int cancelledCount = 0;
        for (Order candidate : expiredCandidates) {
            Order lockedOrder = orderRepository.findByIdWithLock(candidate.getId()).orElse(null);
            if (lockedOrder == null
                    || lockedOrder.getOrderStatus() != Order.OrderStatus.PENDING_PAYMENT
                    || lockedOrder.getPaymentStatus() != Order.PaymentStatus.UNPAID) {
                continue;
            }

            // Reconcile payment outcome before cancelling and restoring stock (BR-04-04)
            ReconciliationResult reconciliation = reconciliationClient.reconcileExpiredOrder(lockedOrder);
            ReconciliationOutcome outcome = reconciliation.outcome();

            if (outcome == ReconciliationOutcome.INDETERMINATE) {
                log.warn("CRON-01: Skipping expired orderCode={} due to indeterminate gateway reconciliation: {}",
                        lockedOrder.getOrderCode(), reconciliation.diagnosticMessage());
                continue;
            }

            Long actorUserId = lockedOrder.getUser() != null ? lockedOrder.getUser().getId() : null;

            if (outcome == ReconciliationOutcome.VERIFIED_PAID) {
                // Gateway confirms funds were collected; advance order without deducting stock again (BR-04-02)
                lockedOrder.setPaymentStatus(Order.PaymentStatus.PAID);
                lockedOrder.setOrderStatus(Order.OrderStatus.PENDING_CONFIRMATION);
                if (reconciliation.payDate() != null) {
                    lockedOrder.setVnpayPayDate(reconciliation.payDate());
                }
                if (reconciliation.bankCode() != null) {
                    lockedOrder.setVnpayBankCode(reconciliation.bankCode());
                }
                orderRepository.save(lockedOrder);

                Map<String, Object> paidAudit = new LinkedHashMap<>();
                paidAudit.put("orderCode", lockedOrder.getOrderCode());
                paidAudit.put("reconciliationOutcome", outcome.name());
                paidAudit.put("newOrderStatus", Order.OrderStatus.PENDING_CONFIRMATION.name());
                paidAudit.put("newPaymentStatus", Order.PaymentStatus.PAID.name());
                auditLogService.record(
                        actorUserId,
                        "VNPAY_TIMEOUT_RECONCILED_PAID",
                        "orders",
                        lockedOrder.getId(),
                        toJson(paidAudit),
                        "SYSTEM_CRON_01"
                );
                log.info("CRON-01: Reconciled expired orderCode={} as PAID; advanced to PENDING_CONFIRMATION.",
                        lockedOrder.getOrderCode());
                continue;
            }

            // Outcome is VERIFIED_UNPAID_OR_FAILED: cancel order, restore stock once, and release voucher
            lockedOrder.setPaymentStatus(Order.PaymentStatus.UNPAID);
            lockedOrder.setCancelReason("Expired 15-minute VNPay payment TTL (reconciled unpaid)");
            orderRepository.save(lockedOrder);

            // Restore inventory deducted at order creation (UC11) exactly once
            stockMutationService.restoreCancelledOrder(lockedOrder.getId(), actorUserId);

            // Release redeemed voucher quota in the same transaction
            orderVoucherReleasePort.releaseOrderVoucherIfPresent(lockedOrder.getId());

            Map<String, Object> cancelAudit = new LinkedHashMap<>();
            cancelAudit.put("orderCode", lockedOrder.getOrderCode());
            cancelAudit.put("reconciliationOutcome", outcome.name());
            cancelAudit.put("diagnosticMessage", reconciliation.diagnosticMessage());
            cancelAudit.put("newOrderStatus", Order.OrderStatus.CANCELLED.name());
            cancelAudit.put("newPaymentStatus", Order.PaymentStatus.UNPAID.name());
            cancelAudit.put("stockRestored", true);

            auditLogService.record(
                    actorUserId,
                    "VNPAY_TIMEOUT_CANCELLED_RESTOCK",
                    "orders",
                    lockedOrder.getId(),
                    toJson(cancelAudit),
                    "SYSTEM_CRON_01"
            );

            cancelledCount++;
            log.info("CRON-01: Cancelled expired unpaid VNPay orderCode={} and restored deducted inventory once.",
                    lockedOrder.getOrderCode());
        }

        return cancelledCount;
    }

    private String toJson(Object payload) {
        if (objectMapper == null || payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize CRON-01 audit log details: {}", ex.getMessage());
            return null;
        }
    }
}
