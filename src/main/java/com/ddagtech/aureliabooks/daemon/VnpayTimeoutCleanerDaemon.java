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
import com.ddagtech.aureliabooks.service.impl.VNPayServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRON-01: VNPay Timeout Cleaner & Reconciliation Daemon (V2.4 §5.2 / BR-04-04 / C07).
 *
 * Scans PENDING_PAYMENT orders that have exceeded the 15-minute TTL window.
 * Per V2.4 Invariant, expiration of the payment window or browser closure does NOT
 * automatically prove payment failure; the daemon reconciles unverified transactions before
 * cancelling the order, restoring the inventory deducted at order creation (UC11) exactly once,
 * and releasing any redeemed voucher quota.
 */
@Slf4j
@Component
public class VnpayTimeoutCleanerDaemon {

    private final OrderRepository orderRepository;
    private final VNPayConfig vnPayConfig;
    private final VNPayGatewayReconciliationClient reconciliationClient;
    private final StockMutationService stockMutationService;
    private final OrderVoucherReleasePort orderVoucherReleasePort;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private EntityManager entityManager;
    private Clock clock = Clock.system(VNPayServiceImpl.VIETNAM_ZONE);

    @Autowired
    public VnpayTimeoutCleanerDaemon(
            OrderRepository orderRepository,
            VNPayConfig vnPayConfig,
            VNPayGatewayReconciliationClient reconciliationClient,
            StockMutationService stockMutationService,
            OrderVoucherReleasePort orderVoucherReleasePort,
            AuditLogService auditLogService,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.vnPayConfig = vnPayConfig;
        this.reconciliationClient = reconciliationClient;
        this.stockMutationService = stockMutationService;
        this.orderVoucherReleasePort = orderVoucherReleasePort;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    /**
     * Executes every 60 seconds to reconcile and clean up expired PENDING_PAYMENT orders.
     *
     * @return number of expired orders cancelled and restocked during this tick
     */
    @Scheduled(fixedDelayString = "${app.cron.vnpay-timeout-ms:60000}")
    @Transactional(rollbackFor = Exception.class)
    public int cleanExpiredPendingPaymentOrders() {
        int expireMinutes = (vnPayConfig != null && vnPayConfig.getExpireMinutes() > 0)
                ? vnPayConfig.getExpireMinutes()
                : 15;
        LocalDateTime cutoffTime = LocalDateTime.now(clock.withZone(VNPayServiceImpl.VIETNAM_ZONE))
                .minusMinutes(expireMinutes);

        List<Order> expiredCandidates = orderRepository.findExpiredPendingPaymentOrders(cutoffTime);
        if (expiredCandidates == null || expiredCandidates.isEmpty()) {
            return 0;
        }

        int cancelledCount = 0;
        for (Order candidate : expiredCandidates) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            Order lockedOrder = orderRepository.findByIdWithLock(candidate.getId()).orElse(null);
            if (lockedOrder == null) {
                continue;
            }

            // F02: A locking query does not overwrite an entity already held in the persistence context.
            // Refresh under PESSIMISTIC_WRITE so any concurrent IPN commit is immediately visible.
            if (entityManager != null) {
                entityManager.refresh(lockedOrder, LockModeType.PESSIMISTIC_WRITE);
            }

            if (lockedOrder.getOrderStatus() != Order.OrderStatus.PENDING_PAYMENT
                    || lockedOrder.getPaymentStatus() != Order.PaymentStatus.UNPAID
                    || lockedOrder.getStockRestoredAt() != null) {
                continue;
            }

            // Reconcile payment outcome before cancelling and restoring stock (BR-04-04 / F01)
            ReconciliationResult reconciliation = reconciliationClient.reconcileExpiredOrder(lockedOrder);
            if (reconciliation == null || reconciliation.outcome() == null) {
                continue;
            }
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

            // Release redeemed voucher quota in the same transaction (propagates SQL failures - F06)
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
