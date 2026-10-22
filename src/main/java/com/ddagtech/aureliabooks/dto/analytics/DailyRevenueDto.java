package com.ddagtech.aureliabooks.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyRevenueDto {

    private static final Locale VI_VN = Locale.of("vi", "VN");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM");

    private LocalDate date;

    @Builder.Default
    private long orderCount = 0L;

    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;

    public String getFormattedDate() {
        return date != null ? date.format(DATE_FMT) : "";
    }

    public String getFormattedRevenue() {
        if (revenue == null) {
            return "0 ₫";
        }
        NumberFormat nf = NumberFormat.getInstance(VI_VN);
        return nf.format(revenue) + " ₫";
    }
}
