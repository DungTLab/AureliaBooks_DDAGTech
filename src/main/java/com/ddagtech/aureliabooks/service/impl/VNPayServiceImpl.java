package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.VNPayIpnResponse;
import com.ddagtech.aureliabooks.dto.response.VNPayPaymentResultView;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.service.OrderVoucherReleasePort;
import com.ddagtech.aureliabooks.service.StockMutationService;
import com.ddagtech.aureliabooks.service.VNPayService;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Implementation of {@link VNPayService} for UC11.1 (Pay via VNPay).
 *
 * Strictly enforces V2.4 Business Rules:
 * - BR-04-01: Independent order_status (6 states) and payment_status (3 states).
 * - BR-04-02: Stock is already deducted at order creation (UC11). IPN payment success transitions
 *   order_status to PENDING_CONFIRMATION and payment_status to PAID without deducting stock again.
 *   Verified terminal payment failure or cancellation restores deducted stock exactly once and releases vouchers.
 *   Indeterminate or incomplete gateway statuses keep the order in PENDING_PAYMENT for reconciliation.
 * - BR-04-04 / 11.1.0.E6: Late payment success arriving after an order was already CANCELLED is logged
 *   for financial reconciliation without reactivating the order or deducting stock again.
 * - BR-07-01: Append-only audit trail recorded for every gateway settlement or failure event.
 */
@Slf4j
@Service
public class VNPayServiceImpl implements VNPayService {

    public static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final DateTimeFormatter VNPAY_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Recognized VNPay terminal failure and cancellation response codes when paired with vnp_TransactionStatus = "02".
     */
    private static final Set<String> TERMINAL_FAILURE_RESPONSE_CODES = Set.of(
            "02", "09", "10", "11", "12", "13", "24", "51", "65", "75", "79"
    );

    public enum GatewayPaymentOutcome {
        VERIFIED_SUCCESS,
        VERIFIED_TERMINAL_FAILURE,
        INDETERMINATE
    }

    private final VNPayConfig vnPayConfig;
    private final OrderRepository orderRepository;
    private final StockMutationService stockMutationService;
    private final OrderVoucherReleasePort orderVoucherReleasePort;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private EntityManager entityManager;
    private CustomerIdentity customerIdentity;
    private Clock clock = Clock.system(VIETNAM_ZONE);

    @Autowired
    public VNPayServiceImpl(
            VNPayConfig vnPayConfig,
            OrderRepository orderRepository,
            StockMutationService stockMutationService,
            OrderVoucherReleasePort orderVoucherReleasePort,
            AuditLogService auditLogService,
            ObjectMapper objectMapper
    ) {
        this.vnPayConfig = vnPayConfig;
        this.orderRepository = orderRepository;
        this.stockMutationService = stockMutationService;
        this.orderVoucherReleasePort = orderVoucherReleasePort;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Autowired(required = false)
    public void setCustomerIdentity(CustomerIdentity customerIdentity) {
        this.customerIdentity = customerIdentity;
    }

    /**
     * Allows tests to inject a deterministic clock for deadline boundary verification.
     */
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    /**
     * Classifies VNPay callback/query parameters into verified success, verified terminal failure, or indeterminate.
     * Never defaults missing vnp_TransactionStatus to vnp_ResponseCode.
     */
    public static GatewayPaymentOutcome classifyGatewayOutcome(String responseCode, String transactionStatus) {
        if (responseCode == null || responseCode.isBlank()
                || transactionStatus == null || transactionStatus.isBlank()) {
            return GatewayPaymentOutcome.INDETERMINATE;
        }
        String cleanRsp = responseCode.trim();
        String cleanTxStatus = transactionStatus.trim();

        if ("00".equals(cleanRsp) && "00".equals(cleanTxStatus)) {
            return GatewayPaymentOutcome.VERIFIED_SUCCESS;
        }
        if ("02".equals(cleanTxStatus) && TERMINAL_FAILURE_RESPONSE_CODES.contains(cleanRsp)) {
            return GatewayPaymentOutcome.VERIFIED_TERMINAL_FAILURE;
        }
        return GatewayPaymentOutcome.INDETERMINATE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createPaymentUrl(Long orderId, String clientIp) {
        return createPaymentUrl(null, orderId, clientIp);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createPaymentUrl(Long authenticatedUserId, Long orderId, String clientIp) {
        if (orderId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Order ID must not be null.");
        }
        if (vnPayConfig == null || !vnPayConfig.isConfigured()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "VNPay gateway credentials are not configured.");
        }

        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found."));
        refreshUnderLockIfManaged(order);

        // Enforce customer role and order ownership (F04)
        verifyOrderOwnership(order, authenticatedUserId);

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

        // Enforce fixed 15-minute payment window anchored to order creation time without extending on retry (F07)
        int expireMinutes = vnPayConfig.getExpireMinutes() > 0 ? vnPayConfig.getExpireMinutes() : 15;
        ZonedDateTime nowVn = ZonedDateTime.now(clock.withZone(VIETNAM_ZONE));
        LocalDateTime anchorTime = order.getCreatedAt() != null
                ? order.getCreatedAt()
                : order.getStockDeductedAt();
        ZonedDateTime deadlineVn = anchorTime != null
                ? anchorTime.atZone(VIETNAM_ZONE).plusMinutes(expireMinutes)
                : nowVn.plusMinutes(expireMinutes);

        if (!nowVn.isBefore(deadlineVn)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "VNPay payment window (15 minutes) has expired for this order.");
        }

        String createDate = nowVn.format(VNPAY_TIMESTAMP_FORMATTER);
        String expireDate = deadlineVn.format(VNPAY_TIMESTAMP_FORMATTER);

        String txnRef = order.getVnpayTxnRef();
        if (txnRef == null || txnRef.isBlank()) {
            txnRef = order.getOrderCode();
            order.setVnpayTxnRef(txnRef);
        }
        // Persist the exact PAY vnp_CreateDate durably on the Order entity for QueryDR reconciliation across restarts (F10)
        order.setVnpayCreateDate(createDate);
        orderRepository.save(order);

        long amountInMinorUnits = order.getFinalTotalAmount()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();

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
        vnpParams.put("vnp_CreateDate", createDate);
        vnpParams.put("vnp_ExpireDate", expireDate);

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

        // Fail closed when gateway credentials are missing or blank (F03)
        if (vnPayConfig == null || !vnPayConfig.isConfigured()) {
            log.warn("VNPay IPN rejected: gateway credentials are not configured.");
            return VNPayIpnResponse.of("97", "Invalid Checksum");
        }

        // 1. Verify HMAC-SHA512 cryptographic checksum in constant time
        if (!VNPayUtil.verifySignature(params, vnPayConfig.getHashSecret())) {
            log.warn("VNPay IPN signature verification failed from IP={} for txnRef={}",
                    sourceIp, params.get("vnp_TxnRef"));
            return VNPayIpnResponse.of("97", "Invalid Checksum");
        }

        String incomingTmnCode = params.get("vnp_TmnCode");
        if (incomingTmnCode != null && !incomingTmnCode.isBlank()
                && !vnPayConfig.getTmnCode().trim().equals(incomingTmnCode.trim())) {
            log.warn("VNPay IPN terminal code mismatch: expected={}, actual={}",
                    vnPayConfig.getTmnCode(), incomingTmnCode);
            return VNPayIpnResponse.of("97", "Invalid Checksum");
        }

        String txnRef = params.get("vnp_TxnRef");
        if (txnRef == null || txnRef.isBlank()) {
            return VNPayIpnResponse.of("01", "Order not Found");
        }

        // 2. Acquire pessimistic write lock on the target order and refresh state from DB
        Optional<Order> lockedOrderOpt = orderRepository.findByVnpayTxnRefWithLock(txnRef);
        if (lockedOrderOpt.isEmpty()) {
            return VNPayIpnResponse.of("01", "Order not Found");
        }
        Order order = lockedOrderOpt.get();
        refreshUnderLockIfManaged(order);

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

        // 4. Strictly classify gateway outcome without defaulting missing vnp_TransactionStatus (F05)
        String responseCode = params.get("vnp_ResponseCode");
        String transactionStatus = params.get("vnp_TransactionStatus");
        GatewayPaymentOutcome outcome = classifyGatewayOutcome(responseCode, transactionStatus);

        // 5. Idempotency & Late-Success Reconciliation (11.1.0.E6)
        if (order.getOrderStatus() != Order.OrderStatus.PENDING_PAYMENT
                || order.getPaymentStatus() != Order.PaymentStatus.UNPAID) {

            // Scenario 11.1.0.E6: Late payment success arriving after order was already CANCELLED
            if (order.getOrderStatus() == Order.OrderStatus.CANCELLED
                    && outcome == GatewayPaymentOutcome.VERIFIED_SUCCESS) {
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

            log.info("Duplicate or out-of-order VNPay IPN ignored for orderCode={} (orderStatus={}, paymentStatus={}).",
                    order.getOrderCode(), order.getOrderStatus(), order.getPaymentStatus());
            return VNPayIpnResponse.of("02", "Order already confirmed");
        }

        Long actorUserId = order.getUser() != null ? order.getUser().getId() : null;

        // 6. Branch A: Verified Payment Success (00/00) -> PAID + PENDING_CONFIRMATION (NO second stock deduction!)
        if (outcome == GatewayPaymentOutcome.VERIFIED_SUCCESS) {
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

        // 7. Branch B: Indeterminate / Incomplete Outcome -> Keep PENDING_PAYMENT & UNPAID for reconciliation (F05)
        if (outcome == GatewayPaymentOutcome.INDETERMINATE) {
            Map<String, Object> indeterminateAudit = new LinkedHashMap<>();
            indeterminateAudit.put("orderCode", order.getOrderCode());
            indeterminateAudit.put("vnpTxnRef", txnRef);
            indeterminateAudit.put("vnpResponseCode", responseCode);
            indeterminateAudit.put("vnpTransactionStatus", transactionStatus);
            indeterminateAudit.put("orderStatus", order.getOrderStatus().name());
            indeterminateAudit.put("paymentStatus", order.getPaymentStatus().name());

            auditLogService.record(
                    actorUserId,
                    "VNPAY_IPN_INDETERMINATE",
                    "orders",
                    order.getId(),
                    toJson(indeterminateAudit),
                    sourceIp
            );
            log.warn("VNPay IPN outcome indeterminate (rspCode={}, txStatus={}) for orderCode={}: keeping PENDING_PAYMENT.",
                    responseCode, transactionStatus, order.getOrderCode());
            return VNPayIpnResponse.of("99", "Indeterminate or incomplete transaction status");
        }

        // 8. Branch C: Verified Terminal Payment Failure or Customer Cancellation -> CANCELLED + UNPAID + Restock Once + Release Voucher
        order.setPaymentStatus(Order.PaymentStatus.UNPAID);
        order.setCancelReason("VNPay payment failed or cancelled (RspCode: " + responseCode + ", TxStatus: " + transactionStatus + ")");
        orderRepository.save(order);

        // Restore inventory deducted at order creation (UC11) exactly once (stock_restored_at, stock_restore_reason = CANCELLED)
        stockMutationService.restoreCancelledOrder(order.getId(), actorUserId);

        // Release any redeemed voucher quota in the same transaction (propagates SQL errors to roll back atomically - F06)
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
        log.info("VNPay IPN reported verified failed/cancelled payment (code={}, txStatus={}) for orderCode={}: cancelled order and restored stock once.",
                responseCode, transactionStatus, order.getOrderCode());
        return VNPayIpnResponse.of("00", "Confirm Success");
    }

    @Override
    @Transactional(readOnly = true)
    public VNPayPaymentResultView evaluateBrowserReturn(Map<String, String> params) {
        boolean validSignature = vnPayConfig != null
                && vnPayConfig.isConfigured()
                && VNPayUtil.verifySignature(params, vnPayConfig.getHashSecret());

        String txnRef = params != null ? params.getOrDefault("vnp_TxnRef", "") : "";
        String responseCode = params != null ? params.getOrDefault("vnp_ResponseCode", "") : "";
        String transactionStatus = params != null ? params.getOrDefault("vnp_TransactionStatus", "") : "";
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

        GatewayPaymentOutcome gatewayOutcome = classifyGatewayOutcome(responseCode, transactionStatus);
        boolean verifiedSuccess;
        String displayMessage;

        if (gatewayOutcome == GatewayPaymentOutcome.VERIFIED_SUCCESS) {
            if ("CANCELLED".equals(orderStatus)) {
                // F08: Late payment success on an already cancelled order must report reconciliation/refund review
                verifiedSuccess = false;
                displayMessage = "Đơn hàng đã bị hủy trước đó. Giao dịch thanh toán đến muộn đã được ghi nhận để đối soát và xử lý hoàn tiền.";
            } else if ("PAID".equals(paymentStatus)) {
                verifiedSuccess = true;
                displayMessage = "Giao dịch thanh toán VNPay đã được xác minh thành công. Đơn hàng của bạn đang chờ xác nhận.";
            } else {
                // F08: Browser returned with 00/00 before server-to-server IPN completed
                verifiedSuccess = false;
                displayMessage = "Đã nhận phản hồi từ VNPay. Hệ thống đang đối soát và xác minh kết quả thanh toán.";
            }
        } else if (gatewayOutcome == GatewayPaymentOutcome.VERIFIED_TERMINAL_FAILURE) {
            verifiedSuccess = false;
            displayMessage = "Giao dịch thanh toán VNPay không thành công hoặc đã bị hủy.";
        } else {
            verifiedSuccess = false;
            displayMessage = "Trạng thái giao dịch VNPay chưa hoàn tất hoặc chưa xác định. Hệ thống đang chờ đối soát.";
        }

        return new VNPayPaymentResultView(
                true,
                verifiedSuccess,
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

    private void verifyOrderOwnership(Order order, Long explicitAuthenticatedUserId) {
        if (explicitAuthenticatedUserId != null) {
            if (order.getUser() == null || !explicitAuthenticatedUserId.equals(order.getUser().getId())) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Order does not belong to the authenticated customer.");
            }
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Customer authentication is required to initiate payment.");
        }

        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities != null && !authorities.isEmpty()) {
            boolean hasCustomerRole = authorities.stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(role -> "ROLE_CUSTOMER".equals(role) || "CUSTOMER".equals(role));
            if (!hasCustomerRole) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Only customers can initiate order payment.");
            }
        }

        User owner = order.getUser();
        if (owner == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Order does not belong to the authenticated customer.");
        }

        Object principal = auth.getPrincipal();
        if (customerIdentity != null && (principal instanceof CustomUserDetails
                || principal instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser)) {
            Long resolvedUserId = customerIdentity.resolve(auth);
            if (!resolvedUserId.equals(owner.getId())) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Order does not belong to the authenticated customer.");
            }
            return;
        }

        if (principal instanceof CustomUserDetails cud) {
            if (cud.getId() == null || !cud.getId().equals(owner.getId())) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Order does not belong to the authenticated customer.");
            }
            return;
        }

        if (principal instanceof User principalUser) {
            CustomerIdentity.requireCustomer(principalUser);
            if (principalUser.getId() == null || !principalUser.getId().equals(owner.getId())) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Order does not belong to the authenticated customer.");
            }
            return;
        }

        if (principal instanceof Long principalId) {
            if (!principalId.equals(owner.getId())) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Order does not belong to the authenticated customer.");
            }
            return;
        }

        String authName = (principal instanceof UserDetails ud) ? ud.getUsername() : auth.getName();
        if (authName == null || authName.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Order does not belong to the authenticated customer.");
        }
        boolean matchesOwner = (owner.getEmail() != null && owner.getEmail().equalsIgnoreCase(authName))
                || (owner.getPhone() != null && owner.getPhone().equals(authName))
                || (owner.getId() != null && String.valueOf(owner.getId()).equals(authName));
        if (!matchesOwner) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Order does not belong to the authenticated customer.");
        }
    }

    private void refreshUnderLockIfManaged(Order order) {
        if (entityManager != null && order != null && entityManager.contains(order)) {
            entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        }
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
