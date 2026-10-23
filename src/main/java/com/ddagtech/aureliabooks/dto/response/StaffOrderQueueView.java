package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Order;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StaffOrderQueueView(Long id, String orderCode, String recipientName, LocalDateTime createdAt,
                                   Order.OrderStatus status, Order.PaymentMethod paymentMethod,
                                   Order.PaymentStatus paymentStatus, BigDecimal total,
                                   String customerName, String customerEmail) {
    public boolean needsRefund() { return status == Order.OrderStatus.CANCELLED && paymentStatus == Order.PaymentStatus.PAID; }
    public String statusLabel() { return OrderTimelineStep.label(status); }
    public String paymentLabel() {
        return switch (paymentStatus) {
            case UNPAID -> "Chưa thanh toán";
            case PAID -> "Đã thanh toán";
            case REFUNDED -> "Đã hoàn tiền";
        };
    }
}
