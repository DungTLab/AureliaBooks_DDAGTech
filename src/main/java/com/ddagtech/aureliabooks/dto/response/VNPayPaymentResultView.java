package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.service.impl.VNPayServiceImpl;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable view model rendered on the VNPay payment result screen
 * when the customer returns from the payment gateway (UC11.1).
 */
public record VNPayPaymentResultView(
        boolean validSignature,
        boolean paymentSuccess,
        String orderCode,
        BigDecimal amountVnd,
        String responseCode,
        String transactionStatus,
        String bankCode,
        String payDate,
        String orderStatus,
        String paymentStatus,
        String displayMessage
) {

    private static final Map<String, String> PAYMENT_STATUS_LABELS = Map.of(
            "UNPAID", "Chưa thanh toán",
            "PAID", "Đã thanh toán",
            "REFUNDED", "Đã hoàn tiền"
    );

    /**
     * Resolves the context-aware heading for the payment result page (F08 / F12).
     * Strictly distinguishes:
     * - Invalid cryptographic signature -> "Không Thể Xác Thực Giao Dịch"
     * - Verified PAID after IPN -> "Thanh Toán Thành Công"
     * - Late 00/00 return on an already CANCELLED order -> "Giao Dịch Đến Muộn — Đang Đối Soát"
     * - 00/00 return before IPN or indeterminate/incomplete outcome (e.g., 00/01, 99/99) -> "Đang Xác Minh Thanh Toán"
     * - Verified terminal failure/cancellation (e.g., 24/02) -> "Thanh Toán Không Thành Công"
     */
    public String displayTitle() {
        if (!validSignature) {
            return "Không Thể Xác Thực Giao Dịch";
        }
        if (paymentSuccess) {
            return "Thanh Toán Thành Công";
        }
        VNPayServiceImpl.GatewayPaymentOutcome outcome =
                VNPayServiceImpl.classifyGatewayOutcome(responseCode, transactionStatus);
        if (outcome == VNPayServiceImpl.GatewayPaymentOutcome.VERIFIED_SUCCESS) {
            if ("CANCELLED".equalsIgnoreCase(orderStatus)) {
                return "Giao Dịch Đến Muộn — Đang Đối Soát";
            }
            return "Đang Xác Minh Thanh Toán";
        }
        if (outcome == VNPayServiceImpl.GatewayPaymentOutcome.VERIFIED_TERMINAL_FAILURE) {
            return "Thanh Toán Không Thành Công";
        }
        return "Đang Xác Minh Thanh Toán";
    }

    public String getDisplayTitle() {
        return displayTitle();
    }

    /**
     * Maps the persisted order status enum name to the canonical Vietnamese storefront label.
     *
     * @return localized Vietnamese order status label, or "Chưa xác định" when unavailable
     */
    public String displayOrderStatus() {
        if (orderStatus == null || orderStatus.isBlank() || "UNKNOWN".equalsIgnoreCase(orderStatus)) {
            return "Chưa xác định";
        }
        try {
            return OrderTimelineStep.label(Order.OrderStatus.valueOf(orderStatus.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return "Chưa xác định";
        }
    }

    public String getDisplayOrderStatus() {
        return displayOrderStatus();
    }

    /**
     * Maps the persisted payment status enum name to the canonical Vietnamese storefront label.
     *
     * @return localized Vietnamese payment status label, or "Chưa xác định" when unavailable
     */
    public String displayPaymentStatus() {
        if (paymentStatus == null || paymentStatus.isBlank()) {
            return "Chưa xác định";
        }
        return PAYMENT_STATUS_LABELS.getOrDefault(
                paymentStatus.trim().toUpperCase(Locale.ROOT),
                "Chưa xác định"
        );
    }

    public String getDisplayPaymentStatus() {
        return displayPaymentStatus();
    }

    /**
     * Classifies the visual state tone for template styling without duplicating branching rules in HTML.
     *
     * @return one of {@code success}, {@code reconcile}, {@code pending}, or {@code failure}
     */
    public String statusTone() {
        if (!validSignature) {
            return "failure";
        }
        if (paymentSuccess) {
            return "success";
        }
        VNPayServiceImpl.GatewayPaymentOutcome outcome =
                VNPayServiceImpl.classifyGatewayOutcome(responseCode, transactionStatus);
        if (outcome == VNPayServiceImpl.GatewayPaymentOutcome.VERIFIED_SUCCESS
                && "CANCELLED".equalsIgnoreCase(orderStatus)) {
            return "reconcile";
        }
        if (outcome == VNPayServiceImpl.GatewayPaymentOutcome.VERIFIED_TERMINAL_FAILURE) {
            return "failure";
        }
        return "pending";
    }

    public String getStatusTone() {
        return statusTone();
    }
}
