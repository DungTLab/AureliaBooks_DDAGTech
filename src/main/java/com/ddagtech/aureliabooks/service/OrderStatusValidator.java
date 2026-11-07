package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.Order.OrderStatus;
import com.ddagtech.aureliabooks.exception.IllegalStateTransitionException;
import org.springframework.stereotype.Component;
import java.util.List;

/** Payment reconciliation owns PENDING_PAYMENT -> PENDING_CONFIRMATION, outside the staff graph. */
@Component
public class OrderStatusValidator {
    public List<OrderStatus> allowedTargets(OrderStatus current) {
        if (current == null) return List.of();
        return switch (current) {
            case PENDING_PAYMENT -> List.of();
            case PENDING_CONFIRMATION -> List.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);
            case CONFIRMED -> List.of(OrderStatus.SHIPPING, OrderStatus.CANCELLED);
            case SHIPPING -> List.of(OrderStatus.DELIVERED);
            case DELIVERED, CANCELLED, RETURNED -> List.of();
        };
    }

    public void validateTransition(OrderStatus current, OrderStatus target) {
        if (target == null || !allowedTargets(current).contains(target)) throw new IllegalStateTransitionException(current, target);
    }
}
