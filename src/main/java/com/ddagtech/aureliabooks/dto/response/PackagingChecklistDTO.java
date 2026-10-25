package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.Order;
import java.time.LocalDateTime;
import java.util.List;

public record PackagingChecklistDTO(Long orderId, OrderConfirmationView order,
                                    String trackingNumber, String cancelReason,
                                    List<OrderTimelineStep> timeline, List<Order.OrderStatus> allowedTargets,
                                    String customerName, String customerEmail,
                                    List<OrderActivityView> history, boolean canConfirmRefund, boolean needsRefund,
                                    boolean canConfirmReturn, LocalDateTime stockRestoredAt,
                                    Order.StockRestoreReason stockRestoreReason) {
    public PackagingChecklistDTO {
        timeline = List.copyOf(timeline);
        allowedTargets = List.copyOf(allowedTargets);
        history = List.copyOf(history);
    }

    public PackagingChecklistDTO(Long orderId, OrderConfirmationView order,
                                String trackingNumber, String cancelReason,
                                List<OrderTimelineStep> timeline, List<Order.OrderStatus> allowedTargets,
                                String customerName, String customerEmail,
                                List<OrderActivityView> history, boolean canConfirmRefund, boolean needsRefund) {
        this(orderId, order, trackingNumber, cancelReason, timeline, allowedTargets, customerName, customerEmail,
                history, canConfirmRefund, needsRefund, false, null, null);
    }

    public String targetLabel(Order.OrderStatus status) { return OrderTimelineStep.label(status); }
}
