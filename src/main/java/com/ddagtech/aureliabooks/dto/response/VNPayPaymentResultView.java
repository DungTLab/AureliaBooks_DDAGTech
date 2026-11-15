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

    public String displayTitle() {
        return displayState().title;
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
        return displayState().tone;
    }

    private DisplayState displayState() {
        if (!validSignature) {
            return DisplayState.INVALID_SIGNATURE;
        }
        if (paymentSuccess) {
            return DisplayState.SUCCESS;
        }
        return switch (VNPayServiceImpl.classifyGatewayOutcome(responseCode, transactionStatus)) {
            case VERIFIED_SUCCESS -> "CANCELLED".equalsIgnoreCase(orderStatus)
                    ? DisplayState.RECONCILE : DisplayState.PENDING;
            case VERIFIED_TERMINAL_FAILURE -> DisplayState.FAILURE;
            default -> DisplayState.PENDING;
        };
    }

    private enum DisplayState {
        INVALID_SIGNATURE("Không Thể Xác Thực Giao Dịch", "failure"),
        SUCCESS("Thanh Toán Thành Công", "success"),
        RECONCILE("Giao Dịch Đến Muộn — Đang Đối Soát", "reconcile"),
        FAILURE("Thanh Toán Không Thành Công", "failure"),
        PENDING("Đang Xác Minh Thanh Toán", "pending");

        private final String title;
        private final String tone;

        DisplayState(String title, String tone) {
            this.title = title;
            this.tone = tone;
        }
    }

    public String getStatusTone() {
        return statusTone();
    }
}
