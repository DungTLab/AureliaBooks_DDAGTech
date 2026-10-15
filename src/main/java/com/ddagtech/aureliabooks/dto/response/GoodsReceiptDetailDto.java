package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.GoodsReceipt;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UC22/23: Comprehensive Goods Receipt data including header, immutable posting audit, and line items.
 */
public record GoodsReceiptDetailDto(
        Long id,
        String receiptCode,
        Long supplierId,
        String supplierName,
        String supplierPhone,
        String supplierEmail,
        Long createdByUserId,
        String createdByName,
        GoodsReceipt.Status status,
        BigDecimal totalAmount,
        String note,
        LocalDateTime receivedAt,
        Long receivedByUserId,
        String receivedByName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<GoodsReceiptItemDetailDto> items
) {}
