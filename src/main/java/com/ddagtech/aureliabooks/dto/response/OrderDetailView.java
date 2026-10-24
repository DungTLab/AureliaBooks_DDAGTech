package com.ddagtech.aureliabooks.dto.response;

import java.util.List;

public record OrderDetailView(OrderConfirmationView order, String trackingNumber,
                              String cancelReason, List<OrderTimelineStep> timeline,
                              boolean canCancel, String cancellationNotice) {
    public OrderDetailView { timeline = List.copyOf(timeline); }
}
