package com.ddagtech.aureliabooks.dto.response;

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
}
