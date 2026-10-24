package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.VNPayIpnResponse;
import com.ddagtech.aureliabooks.dto.response.VNPayPaymentResultView;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.service.OrderVoucherReleasePort;
import com.ddagtech.aureliabooks.service.StockMutationService;
import com.ddagtech.aureliabooks.service.VNPayService;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Implementation of {@link VNPayService} for UC11.1 (Pay via VNPay).
 *
 * Strictly enforces V2.3 Business Rules:
 * - BR-04-01: Independent order_status (6 states) and payment_status (3 states).
 * - BR-04-02: Stock is already deducted at order creation (UC11). IPN payment success transitions
 *   order_status to PENDING_CONFIRMATION and payment_status to PAID without deducting stock again.
 *   Verified payment failure or cancellation restores deducted stock exactly once and releases vouchers.
 * - BR-04-04 / 11.1.0.E6: Late payment success arriving after an order was already CANCELLED is logged
 *   for financial reconciliation without reactivating the order or deducting stock again.
 * - BR-07-01: Append-only audit trail recorded for every gateway settlement or failure event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VNPayServiceImpl implements VNPayService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNPAY_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final VNPayConfig vnPayConfig;
    private final OrderRepository orderRepository;
    private final StockMutationService stockMutationService;
    private final OrderVoucherReleasePort orderVoucherReleasePort;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createPaymentUrl(Long orderId, String clientIp) {
        if (orderId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Order ID must not be null.");
        }

        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found."));

        if (order.getPaymentMethod() != Order.PaymentMethod.VNPAY) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Order payment method is not VNPay.");
        }
        if (order.getOrderStatus() != Order.OrderStatus.PENDING_PAYMENT
                || order.getPaymentStatus() != Order.PaymentStatus.UNPAID) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Order is not eligible for VNPay payment initiation.");
        }
        if (order.getStockDeductedAt() == null) {
            throw new AppException(ErrorCode.ORDER_STOCK_NOT_DEDUCTED,
                    "Order inventory must be deducted atomically at checkout before initiating VNPay payment.");
        }

        String txnRef = order.getVnpayTxnRef();
        if (txnRef == null || txnRef.isBlank()) {
            txnRef = order.getOrderCode();
            order.setVnpayTxnRef(txnRef);
            orderRepository.save(order);
        }

        long amountInMinorUnits = order.getFinalTotalAmount()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();

        ZonedDateTime nowVn = ZonedDateTime.now(VIETNAM_ZONE);
        ZonedDateTime expireVn = nowVn.plusMinutes(vnPayConfig.getExpireMinutes());

        Map<String, String> vnpParams = new TreeMap<>();
        vnpParams.put("vnp_Version", vnPayConfig.getVersion());
        vnpParams.put("vnp_Command", vnPayConfig.getCommand());
        vnpParams.put("vnp_TmnCode", vnPayConfig.getTmnCode());
        vnpParams.put("vnp_Amount", String.valueOf(amountInMinorUnits));
        vnpParams.put("vnp_CurrCode", "VND");
        vnpParams.put("vnp_TxnRef", txnRef);
        vnpParams.put("vnp_OrderInfo", "Thanh toan don hang " + order.getOrderCode());
        vnpParams.put("vnp_OrderType", vnPayConfig.getOrderType());
        vnpParams.put("vnp_Locale", "vn");
        vnpParams.put("vnp_ReturnUrl", vnPayConfig.getReturnUrl());
        vnpParams.put("vnp_IpAddr", (clientIp != null && !clientIp.isBlank()) ? clientIp : "127.0.0.1");
        vnpParams.put("vnp_CreateDate", nowVn.format(VNPAY_TIMESTAMP_FORMATTER));
        vnpParams.put("vnp_ExpireDate", expireVn.format(VNPAY_TIMESTAMP_FORMATTER));

        String hashData = VNPayUtil.buildHashData(vnpParams);
        String secureHash = VNPayUtil.hmacSHA512(vnPayConfig.getHashSecret(), hashData);
        String queryString = VNPayUtil.buildQueryString(vnpParams) + "&vnp_SecureHash=" + secureHash;

        return vnPayConfig.getPayUrl() + "?" + queryString;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VNPayIpnResponse processIpnWebhook(Map<String, String> params, String sourceIp) {
        if (params == null || params.isEmpty()) {
            return VNPayIpnResponse.of("99", "Invalid Request Parameters");
        }

        // 1. Verify HMAC-SHA512 cryptographic checksum in constant time
        if (!VNPayUtil.verifySignature(params, vnPayConfig.getHashSecret())) {
            log.warn("VNPay IPN signature verification failed from IP={} for txnRef={}",
                    sourceIp, params.get("vnp_TxnRef"));
            return VNPayIpnResponse.of("97", "Invalid Checksum");
        }

        String txnRef = params.get("vnp_TxnRef");
        if (txnRef == null || txnRef.isBlank()) {
            return VNPayIpnResponse.of("01", "Order not Found");
        }

        // 2. Acquire pessimistic write lock on the target order
        Optional<Order> lockedOrderOpt = orderRepository.findByVnpayTxnRefWithLock(txnRef);
        if (lockedOrderOpt.isEmpty()) {
            return VNPayIpnResponse.of("01", "Order not Found");
        }
        Order order = lockedOrderOpt.get();

        // 3. Validate transaction amount (vnp_Amount is in VND * 100)
        String rawAmount = params.get("vnp_Amount");
        long expectedMinorAmount = order.getFinalTotalAmount()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        try {
            long gatewayMinorAmount = Long.parseLong(rawAmount);
            if (gatewayMinorAmount != expectedMinorAmount) {
                log.warn("VNPay IPN amount mismatch for orderCode={}: expected={}, actual={}",
                        order.getOrderCode(), expectedMinorAmount, gatewayMinorAmount);
                return VNPayIpnResponse.of("04", "Invalid Amount");
            }
        } catch (NumberFormatException ex) {
            return VNPayIpnResponse.of("04", "Invalid Amount");
        }

        String responseCode = params.getOrDefault("vnp_ResponseCode", "");
        String transactionStatus = params.getOrDefault("vnp_TransactionStatus", responseCode);
        boolean isGatewayPaymentSuccess = "00".equals(responseCode) && "00".equals(transactionStatus);

        // 4. Idempotency & Late-Success Reconciliation (11.1.0.E6)
        if (order.getOrderStatus() != Order.OrderStatus.PENDING_PAYMENT
                || order.getPaymentStatus() != Order.PaymentStatus.UNPAID) {

            // Scenario 11.1.0.E6: Late payment success arriving after order was already CANCELLED
            if (order.getOrderStatus() == Order.OrderStatus.CANCELLED && isGatewayPaymentSuccess) {
                order.setVnpayPayDate(params.get("vnp_PayDate"));
                order.setVnpayBankCode(params.get("vnp_BankCode"));
                orderRepository.save(order);

                Map<String, Object> lateAudit = new LinkedHashMap<>();
                lateAudit.put("orderCode", order.getOrderCode());
                lateAudit.put("vnpTxnRef", txnRef);
                lateAudit.put("vnpTransactionNo", params.get("vnp_TransactionNo"));
                lateAudit.put("vnpPayDate", params.get("vnp_PayDate"));
                lateAudit.put("vnpBankCode", params.get("vnp_BankCode"));
                lateAudit.put("orderStatus", order.getOrderStatus().name());
                lateAudit.put("paymentStatus", order.getPaymentStatus().name());
                lateAudit.put("reconciliationNote",
                        "Late VNPay payment success arrived after order cancellation. Do not reactivate order or re-deduct stock; manual refund verification required before setting REFUNDED.");
                Long actorId = order.getUser() != null ? order.getUser().getId() : null;
                auditLogService.record(
                        actorId,
                        "VNPAY_LATE_SUCCESS_RECONCILIATION",
                        "orders",
                        order.getId(),
                        toJson(lateAudit),
                        sourceIp
                );
                log.warn("Late VNPay success detected for already CANCELLED orderCode={}. Logged for financial reconciliation.",
                        order.getOrderCode());
                return VNPayIpnResponse.of("00", "Confirm Success");
            }

            log.info("Duplicate VNPay IPN ignored for orderCode={} (orderStatus={}, paymentStatus={}).",
                    order.getOrderCode(), order.getOrderStatus(), order.getPaymentStatus());
            return VNPayIpnResponse.of("02", "Order already confirmed");
        }

        Long actorUserId = order.getUser() != null ? order.getUser().getId() : null;

        // 5. Branch A: Verified Payment Success (00) -> PAID + PENDING_CONFIRMATION (NO second stock deduction!)
        if (isGatewayPaymentSuccess) {
            order.setPaymentStatus(Order.PaymentStatus.PAID);
            order.setOrderStatus(Order.OrderStatus.PENDING_CONFIRMATION);
            order.setVnpayPayDate(params.get("vnp_PayDate"));
            order.setVnpayBankCode(params.get("vnp_BankCode"));
            orderRepository.save(order);

            Map<String, Object> successAudit = new LinkedHashMap<>();
            successAudit.put("orderCode", order.getOrderCode());
            successAudit.put("vnpTxnRef", txnRef);
            successAudit.put("vnpTransactionNo", params.get("vnp_TransactionNo"));
            successAudit.put("vnpBankCode", params.get("vnp_BankCode"));
            successAudit.put("vnpPayDate", params.get("vnp_PayDate"));
            successAudit.put("newOrderStatus", Order.OrderStatus.PENDING_CONFIRMATION.name());
            successAudit.put("newPaymentStatus", Order.PaymentStatus.PAID.name());
            successAudit.put("stockDeductedAt", order.getStockDeductedAt() != null ? order.getStockDeductedAt().toString() : null);

            auditLogService.record(
                    actorUserId,
                    "VNPAY_PAYMENT_SUCCESS",
                    "orders",
                    order.getId(),
                    toJson(successAudit),
                    sourceIp
            );
            log.info("VNPay IPN payment verified for orderCode={}: transitioned to PENDING_CONFIRMATION & PAID without duplicate stock deduction.",
                    order.getOrderCode());
            return VNPayIpnResponse.of("00", "Confirm Success");
        }

        // 6. Branch B: Verified Payment Failure or Customer Cancellation at Gateway -> CANCELLED + UNPAID + Restock Once + Release Voucher
        order.setPaymentStatus(Order.PaymentStatus.UNPAID);
        order.setCancelReason("VNPay payment failed or cancelled (RspCode: " + responseCode + ")");
        orderRepository.save(order);

        // Restore inventory deducted at order creation (UC11) exactly once (stock_restored_at, stock_restore_reason = CANCELLED)
        stockMutationService.restoreCancelledOrder(order.getId(), actorUserId);

        // Release any redeemed voucher quota in the same transaction (III.3.3.a SQL #8)
        orderVoucherReleasePort.releaseOrderVoucherIfPresent(order.getId());

        Map<String, Object> failAudit = new LinkedHashMap<>();
        failAudit.put("orderCode", order.getOrderCode());
        failAudit.put("vnpTxnRef", txnRef);
        failAudit.put("vnpResponseCode", responseCode);
        failAudit.put("vnpTransactionStatus", transactionStatus);
        failAudit.put("newOrderStatus", Order.OrderStatus.CANCELLED.name());
        failAudit.put("newPaymentStatus", Order.PaymentStatus.UNPAID.name());
        failAudit.put("stockRestored", true);

        auditLogService.record(
                actorUserId,
                "VNPAY_PAYMENT_FAILED_CANCELLED",
                "orders",
                order.getId(),
                toJson(failAudit),
                sourceIp
        );
        log.info("VNPay IPN reported failed/cancelled payment (code={}) for orderCode={}: cancelled order and restored stock once.",
                responseCode, order.getOrderCode());
        return VNPayIpnResponse.of("00", "Confirm Success");
    }

    @Override
    @Transactional(readOnly = true)
    public VNPayPaymentResultView evaluateBrowserReturn(Map<String, String> params) {
        boolean validSignature = VNPayUtil.verifySignature(params, vnPayConfig.getHashSecret());
        String txnRef = params != null ? params.getOrDefault("vnp_TxnRef", "") : "";
        String responseCode = params != null ? params.getOrDefault("vnp_ResponseCode", "") : "";
        String transactionStatus = params != null ? params.getOrDefault("vnp_TransactionStatus", responseCode) : "";
        String bankCode = params != null ? params.getOrDefault("vnp_BankCode", "") : "";
        String payDate = params != null ? params.getOrDefault("vnp_PayDate", "") : "";

        BigDecimal amountVnd = BigDecimal.ZERO;
        if (params != null && params.get("vnp_Amount") != null) {
            try {
                amountVnd = new BigDecimal(params.get("vnp_Amount"))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            } catch (NumberFormatException ignored) {
                amountVnd = BigDecimal.ZERO;
            }
        }

        if (!validSignature) {
            return new VNPayPaymentResultView(
                    false,
                    false,
                    txnRef,
                    amountVnd,
                    responseCode,
                    transactionStatus,
                    bankCode,
                    payDate,
                    "UNKNOWN",
                    "UNKNOWN",
                    "Chữ ký xác thực phản hồi từ cổng thanh toán không hợp lệ."
            );
        }

        Optional<Order> orderOpt = orderRepository.findByOrderCode(txnRef);
        String orderStatus = orderOpt.map(o -> o.getOrderStatus().name()).orElse("PENDING_PAYMENT");
        String paymentStatus = orderOpt.map(o -> o.getPaymentStatus().name()).orElse("UNPAID");
        if (orderOpt.isPresent() && amountVnd.compareTo(BigDecimal.ZERO) == 0) {
            amountVnd = orderOpt.get().getFinalTotalAmount();
        }

        boolean gatewaySuccess = "00".equals(responseCode) && "00".equals(transactionStatus);
        String displayMessage = gatewaySuccess
                ? "Giao dịch thanh toán VNPay thành công. Đơn hàng của bạn đang chờ xác nhận."
                : "Giao dịch thanh toán VNPay không thành công hoặc đã bị hủy.";

        return new VNPayPaymentResultView(
                true,
                gatewaySuccess,
                txnRef,
                amountVnd,
                responseCode,
                transactionStatus,
                bankCode,
                payDate,
                orderStatus,
                paymentStatus,
                displayMessage
        );
    }

    private String toJson(Object value) {
        if (objectMapper == null || value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize VNPay audit payload: {}", ex.getMessage());
            return null;
        }
    }
}
