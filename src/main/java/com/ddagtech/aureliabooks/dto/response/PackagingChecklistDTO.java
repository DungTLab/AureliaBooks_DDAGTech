package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Order;
import java.util.List;

public record PackagingChecklistDTO(Long orderId, OrderConfirmationView order,
                                    String trackingNumber, String cancelReason,
                                    List<OrderTimelineStep> timeline, List<Order.OrderStatus> allowedTargets,
                                    String customerName, String customerEmail,
                                    List<OrderActivityView> history, boolean canConfirmRefund, boolean needsRefund) {
    public PackagingChecklistDTO {
        timeline = List.copyOf(timeline);
        allowedTargets = List.copyOf(allowedTargets);
        history = List.copyOf(history);
    }
    public String targetLabel(Order.OrderStatus status) { return OrderTimelineStep.label(status); }
}
