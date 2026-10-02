package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;

public record ReceiptSummary(Long id, String receiptCode, String status, BigDecimal totalAmount) {}
