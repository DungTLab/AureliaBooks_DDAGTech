package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.dto.response.OrderConfirmationView;
import com.ddagtech.aureliabooks.dto.response.OrderHistoryView;
import com.ddagtech.aureliabooks.dto.response.OrderTimelineStep;
import com.ddagtech.aureliabooks.entity.CartItem;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.OrderItem;
import com.ddagtech.aureliabooks.entity.ShippingAddress;
import com.ddagtech.aureliabooks.entity.User;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Copy point-in-time values only; validation, status decisions, and transaction handling belong to the service. */
@Component
public class OrderMapper {

    public OrderHistoryView toHistory(Order order) {
        return new OrderHistoryView(order.getOrderCode(), order.getCreatedAt(),
                order.getFinalTotalAmount(), order.getOrderStatus(), order.getPaymentStatus());
    }

    public List<OrderTimelineStep> toTimeline(Order order) {
        var steps = new ArrayList<OrderTimelineStep>();
        var status = order.getOrderStatus();
        steps.add(new OrderTimelineStep("Đã đặt hàng", order.getCreatedAt(),
                status == Order.OrderStatus.PENDING_PAYMENT || status == Order.OrderStatus.PENDING_CONFIRMATION));
        if (status != Order.OrderStatus.CANCELLED || order.getConfirmedAt() != null)
            steps.add(new OrderTimelineStep("Đã xác nhận", order.getConfirmedAt(), status == Order.OrderStatus.CONFIRMED));
        if (status != Order.OrderStatus.CANCELLED || order.getShippedAt() != null)
            steps.add(new OrderTimelineStep("Đang giao hàng", order.getShippedAt(), status == Order.OrderStatus.SHIPPING));
        if (status != Order.OrderStatus.CANCELLED || order.getDeliveredAt() != null)
            steps.add(new OrderTimelineStep("Giao thành công", order.getDeliveredAt(), status == Order.OrderStatus.DELIVERED));
        if (status == Order.OrderStatus.CANCELLED)
            steps.add(new OrderTimelineStep("Đã hủy", order.getCancelledAt(), true));
        return List.copyOf(steps);
    }

    /** Build the confirmation from stored name, price, and delivery snapshots rather than current catalog prices. */
    public OrderConfirmationView toConfirmation(Order order) {
        List<OrderConfirmationView.Item> items = order
            .getItems()
            .stream()
            .map(item ->
                new OrderConfirmationView.Item(
                    item.getProduct().getId(),
                    item.getProductNameSnapshot(),
                    item.getQuantity(),
                    item.getUnitPriceSnapshot(),
                    item.getUnitPriceSnapshot().multiply(BigDecimal.valueOf(item.getQuantity()))
                )
            )
            .toList();
        BigDecimal subtotal = items
            .stream()
            .map(OrderConfirmationView.Item::lineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderConfirmationView(
            order.getOrderCode(),
            order.getCreatedAt(),
            order.getShippingRecipientName(),
            order.getShippingPhone(),
            order.getShippingFullAddress(),
            order.getOrderStatus(),
            order.getPaymentMethod(),
            order.getPaymentStatus(),
            items,
            subtotal,
            subtotal.add(order.getShippingFee()).subtract(order.getFinalTotalAmount()).max(BigDecimal.ZERO),
            order.getShippingFee(),
            order.getFinalTotalAmount()
        );
    }

    /** Build order and item entities from validated service data without persisting them or changing stock. */
    public Order toNewOrder(
        User owner,
        ShippingAddress address,
        List<CartItem> items,
        CheckoutRequest request,
        CheckoutView checkout,
        Order.OrderStatus initialStatus
    ) {
        Order order = Order.builder()
            .orderCode(request.getCheckoutToken())
            .user(owner)
            .shippingAddress(address)
            .shippingRecipientName(address.getRecipientName())
            // Use the delivery address phone; a verified account phone is not required.
            .shippingPhone(address.getPhone())
            .shippingFullAddress(fullAddress(address))
            .shippingFee(checkout.shippingFee())
            .finalTotalAmount(checkout.finalTotalAmount())
            .paymentMethod(request.getPaymentMethod())
            .paymentStatus(Order.PaymentStatus.UNPAID)
            .orderStatus(initialStatus)
            .build();
        for (CartItem item : items) {
            order.addItem(
                OrderItem.builder()
                    .product(item.getProduct())
                    .quantity(item.getQuantity())
                    .productNameSnapshot(item.getProduct().getTitle())
                    .unitPriceSnapshot(item.getProduct().getPrice())
                    .build()
            );
        }
        return order;
    }

    /** Flatten the existing address fields into a snapshot that preserves the order's delivery details. */
    public String fullAddress(ShippingAddress address) {
        return java.util.stream.Stream.of(
            address.getDetailedAddress(),
            address.getWard(),
            address.getDistrict(),
            address.getProvince()
        )
            .filter(value -> value != null && !value.isBlank())
            .collect(java.util.stream.Collectors.joining(", "));
    }
}
