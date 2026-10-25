package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Voucher;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VoucherSummary(Long id, String code, String description, Voucher.Scope scope,
        Voucher.Type type, BigDecimal discountPercentage, BigDecimal discountAmount,
        BigDecimal maxDiscountAmount, BigDecimal minOrderValue, LocalDateTime startDate,
        LocalDateTime endDate, int remainingUses, boolean active, long redemptions) {
}
