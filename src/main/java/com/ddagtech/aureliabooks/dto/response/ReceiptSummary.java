package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReceiptSummary(
        Long id,
        String receiptCode,
        String status,
        BigDecimal totalAmount,
        String supplierName,
        String createdByName,
        String receivedByName,
        LocalDateTime createdAt,
        LocalDateTime receivedAt,
        Integer totalItems
) {
    public ReceiptSummary(Long id, String receiptCode, String status, BigDecimal totalAmount) {
        this(id, receiptCode, status, totalAmount, null, null, null, null, null, 0);
    }
}
