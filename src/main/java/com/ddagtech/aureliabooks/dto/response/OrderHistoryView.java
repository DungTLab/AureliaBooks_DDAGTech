package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Order;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderHistoryView(String orderCode, LocalDateTime createdAt,
                               BigDecimal finalTotalAmount, Order.OrderStatus orderStatus,
                               Order.PaymentStatus paymentStatus) {
    public String statusLabel() {
        return OrderTimelineStep.label(orderStatus);
    }
}
