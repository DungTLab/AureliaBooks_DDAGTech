package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.CancelOrderRequest;
import com.ddagtech.aureliabooks.dto.response.OrderDetailView;
import com.ddagtech.aureliabooks.dto.response.OrderHistoryView;
import org.springframework.data.domain.Page;
import com.ddagtech.aureliabooks.entity.Order;

public interface CustomerOrderService {
    Page<OrderHistoryView> getMyOrders(Long userId, int page, Order.OrderStatus status);
    OrderDetailView getMyOrderDetail(Long userId, String orderCode);
    void cancelMyOrder(Long userId, String orderCode, CancelOrderRequest request);
}
