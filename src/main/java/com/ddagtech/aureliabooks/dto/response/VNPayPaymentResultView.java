package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.service.impl.VNPayServiceImpl;

import java.math.BigDecimal;

/**
 * Immutable view model rendered on SCR-ST-13 (Payment & Order Result)
 * when the customer returns from the VNPay payment gateway (UC11.1).
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

    /**
     * Resolves the context-aware heading for SCR-ST-13 (F08 / F12).
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
}
