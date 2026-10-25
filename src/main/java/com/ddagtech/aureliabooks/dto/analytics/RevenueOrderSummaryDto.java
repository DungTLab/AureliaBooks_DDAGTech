package com.ddagtech.aureliabooks.dto.analytics;

import com.ddagtech.aureliabooks.entity.Order;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueOrderSummaryDto {

    private static final Locale VI_VN = Locale.of("vi", "VN");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private String orderCode;
    private String recipientName;
    private String phone;
    private Order.PaymentMethod paymentMethod;
    private LocalDateTime createdAt;
    private LocalDateTime deliveredAt;

    @Builder.Default
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal finalTotalAmount = BigDecimal.ZERO;

    public String getFormattedCreatedAt() {
        return createdAt != null ? createdAt.format(DATETIME_FMT) : "";
    }

    public String getFormattedDeliveredAt() {
        return deliveredAt != null ? deliveredAt.format(DATETIME_FMT) : "";
    }

    public String getFormattedShippingFee() {
        return formatCurrency(shippingFee);
    }

    public String getFormattedFinalTotalAmount() {
        return formatCurrency(finalTotalAmount);
    }

    public String getPaymentMethodLabel() {
        if (paymentMethod == null) {
            return "N/A";
        }
        return paymentMethod == Order.PaymentMethod.COD ? "COD (Tiền mặt)" : "VNPAY (Điện tử)";
    }

    private static String formatCurrency(BigDecimal amount) {
        if (amount == null) {
            return "0 ₫";
        }
        NumberFormat nf = NumberFormat.getInstance(VI_VN);
        return nf.format(amount) + " ₫";
    }
}
