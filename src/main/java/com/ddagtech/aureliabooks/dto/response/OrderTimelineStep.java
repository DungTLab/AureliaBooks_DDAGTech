package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Order;
import java.time.LocalDateTime;

/** Only persisted timestamps count as completed milestones. */
public record OrderTimelineStep(String label, LocalDateTime occurredAt, boolean current) {
    public static String label(Order.OrderStatus status) {
        return switch (status) {
            case PENDING_PAYMENT -> "Chờ thanh toán";
            case PENDING_CONFIRMATION -> "Chờ xác nhận";
            case CONFIRMED -> "Đã xác nhận";
            case SHIPPING -> "Đang giao hàng";
            case DELIVERED -> "Giao thành công";
            case RETURNED -> "Đã trả hàng";
            case CANCELLED -> "Đã hủy";
        };
    }
}
