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
public class PaymentBreakdownDto {

    private static final Locale VI_VN = Locale.of("vi", "VN");

    @Builder.Default
    private long codOrdersCount = 0L;

    @Builder.Default
    private BigDecimal codRevenue = BigDecimal.ZERO;

    @Builder.Default
    private long vnpayOrdersCount = 0L;

    @Builder.Default
    private BigDecimal vnpayRevenue = BigDecimal.ZERO;

    @Builder.Default
    private double electronicSharePercent = 0.0;

    public String getFormattedCodRevenue() {
        return formatCurrency(codRevenue);
    }

    public String getFormattedVnpayRevenue() {
        return formatCurrency(vnpayRevenue);
    }

    public String getFormattedElectronicSharePercent() {
        return String.format(Locale.US, "%.1f%%", electronicSharePercent);
    }

    public boolean isMeetingElectronicTarget() {
        return electronicSharePercent >= 40.0;
    }

    private static String formatCurrency(BigDecimal amount) {
        if (amount == null) {
            return "0 ₫";
        }
        NumberFormat nf = NumberFormat.getInstance(VI_VN);
        return nf.format(amount) + " ₫";
    }
}
