package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Voucher;
import java.math.BigDecimal;

public record VoucherQuote(Long voucherId, String code, Voucher.Scope scope,
        BigDecimal merchandiseSubtotal, BigDecimal shippingFee,
        BigDecimal discountAmount, BigDecimal payableTotal) {
}
