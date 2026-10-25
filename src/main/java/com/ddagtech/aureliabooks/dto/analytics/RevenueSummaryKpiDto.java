package com.ddagtech.aureliabooks.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueSummaryKpiDto {

    private static final Locale VI_VN = Locale.of("vi", "VN");

    @Builder.Default
    private long totalOrders = 0L;

    @Builder.Default
    private BigDecimal grossRevenue = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal totalShippingFee = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal averageOrderValue = BigDecimal.ZERO;

    public String getFormattedGrossRevenue() {
        return formatCurrency(grossRevenue);
    }

    public String getFormattedTotalShippingFee() {
        return formatCurrency(totalShippingFee);
    }

    public String getFormattedAverageOrderValue() {
        return formatCurrency(averageOrderValue);
    }

    private static String formatCurrency(BigDecimal amount) {
        if (amount == null) {
            return "0 ₫";
        }
        NumberFormat nf = NumberFormat.getInstance(VI_VN);
        return nf.format(amount) + " ₫";
    }
}
