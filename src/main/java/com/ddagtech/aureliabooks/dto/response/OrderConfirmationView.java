package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Expose persisted snapshots for display and printing, with independent order and payment statuses. */
public record OrderConfirmationView(
        String orderCode, LocalDateTime createdAt,
        String recipientName, String phone, String fullAddress,
        Order.OrderStatus orderStatus, Order.PaymentMethod paymentMethod,
        Order.PaymentStatus paymentStatus, List<Item> items,
        BigDecimal merchandiseSubtotal, BigDecimal discount,
        BigDecimal shippingFee, BigDecimal finalTotalAmount
) {
    public OrderConfirmationView { items = List.copyOf(items); }

    public String orderStatusLabel() {
        return switch (orderStatus) {
            case PENDING_PAYMENT -> "Chờ thanh toán";
            case PENDING_CONFIRMATION -> "Chờ xác nhận";
            case CONFIRMED -> "Đã xác nhận";
            case SHIPPING -> "Đang giao hàng";
            case DELIVERED -> "Giao thành công";
            case RETURNED -> "Đã trả hàng";
            case CANCELLED -> "Đã hủy";
        };
    }

    public String paymentStatusLabel() {
        return switch (paymentStatus) {
            case UNPAID -> "Chưa thanh toán";
            case PAID -> "Đã thanh toán";
            case REFUNDED -> "Đã hoàn tiền";
        };
    }

    /** Allow payment retry only for an unpaid VNPay order that is still pending payment. */
    public boolean awaitingOnlinePayment() {
        return paymentMethod == Order.PaymentMethod.VNPAY
                && orderStatus == Order.OrderStatus.PENDING_PAYMENT
                && paymentStatus == Order.PaymentStatus.UNPAID;
    }

    public record Item(Long productId, String title, Integer quantity,
                       BigDecimal unitPrice, BigDecimal lineTotal) {}
}
