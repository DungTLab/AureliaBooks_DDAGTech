package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Inspection DTO for order details displayed in history ledger modal (UC25 / RDS 5.5 Step 7).
 */
public record OrderDetailModalDto(
        Long id,
        String orderCode,
        LocalDateTime createdAt,
        String orderStatus,
        String paymentMethod,
        String paymentStatus,
        String recipientName,
        String phone,
        String fullAddress,
        BigDecimal shippingFee,
        BigDecimal finalTotalAmount,
        String cancelReason,
        LocalDateTime stockDeductedAt,
        LocalDateTime stockRestoredAt,
        String stockRestoreReason,
        List<OrderItemDetailDto> items
) {
    public record OrderItemDetailDto(
            Long productId,
            String productTitle,
            String barcode,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal
    ) {}
}
