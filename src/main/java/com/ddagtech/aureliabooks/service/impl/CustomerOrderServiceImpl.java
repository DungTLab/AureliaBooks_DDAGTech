package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.CancelOrderRequest;
import com.ddagtech.aureliabooks.dto.response.OrderDetailView;
import com.ddagtech.aureliabooks.dto.response.OrderHistoryView;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.OrderMapper;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.service.CustomerOrderService;
import com.ddagtech.aureliabooks.service.StockMutationService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerOrderServiceImpl implements CustomerOrderService {
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final StockMutationService stockService;
    private final AuditLogService auditLogService;
    private final EntityManager entityManager;

    @Override
    public Page<OrderHistoryView> getMyOrders(Long userId, int page, Order.OrderStatus status) {
        requireAuthenticatedUser(userId);
        var pageable = PageRequest.of(Math.max(0, page), 10,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<Order> orderPage;
        if (status == null) {
            orderPage = orderRepository.findByUser_Id(userId, pageable);
        } else {
            orderPage = orderRepository.findByUser_IdAndOrderStatus(userId, status, pageable);
        }
        return orderPage.map(orderMapper::toHistory);
    }

    @Override
    public OrderDetailView getMyOrderDetail(Long userId, String orderCode) {
        requireAuthenticatedUser(userId);
        Order order = orderRepository.findByOrderCodeAndUser_Id(orderCode, userId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        String notice = null;
        if (hasUnverifiedOnlinePayment(order)) {
            notice = ErrorCode.ORDER_PAYMENT_OUTCOME_UNVERIFIED.getMessage();
        } else if (order.getPaymentStatus() == Order.PaymentStatus.PAID) {
            notice = "Đơn đã thanh toán. Hủy đơn không tự động hoàn tiền; vui lòng liên hệ cửa hàng để xử lý hoàn tiền.";
        }
        return new OrderDetailView(
                orderMapper.toConfirmation(order),
                order.getTrackingNumber(),
                order.getCancelReason(),
                orderMapper.toTimeline(order),
                canCancelOrder(order) && !hasUnverifiedOnlinePayment(order),
                notice);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelMyOrder(Long userId, String orderCode, CancelOrderRequest request) {
        requireAuthenticatedUser(userId);
        if (request == null || request.reason() == null || request.reason().isBlank()
                || request.reason().length() > 255) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Lý do hủy phải có từ 1 đến 255 ký tự.");
        }
        Order order = orderRepository.findOwnedWithLock(orderCode, userId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        // A locking query alone does not refresh an entity already present in the persistence context.
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        if (!order.getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getOrderStatus() == Order.OrderStatus.CANCELLED) {
            return;
        }
        if (!canCancelOrder(order)) {
            throw new AppException(ErrorCode.ORDER_NOT_ELIGIBLE_FOR_CANCEL);
        }
        if (hasUnverifiedOnlinePayment(order)) {
            throw new AppException(ErrorCode.ORDER_PAYMENT_OUTCOME_UNVERIFIED);
        }
        order.setCancelReason(request.reason().strip());
        // The stock engine joins this transaction and owns cancellation timestamps and stock markers.
        stockService.restoreCancelledOrder(order.getId(), userId);
        auditLogService.record(userId, "ORDER_CANCELLED_BY_CUSTOMER", "orders", order.getId(), null, null);
    }

    private boolean canCancelOrder(Order order) {
        return order.getShippedAt() == null && order.getDeliveredAt() == null
                && order.getCancelledAt() == null
                && order.getStockRestoredAt() == null
                && switch (order.getOrderStatus()) {
                    case PENDING_PAYMENT, PENDING_CONFIRMATION, CONFIRMED -> true;
                    default -> false;
                };
    }

    private boolean hasUnverifiedOnlinePayment(Order order) {
        return order.getPaymentMethod() == Order.PaymentMethod.VNPAY
                && order.getPaymentStatus() == Order.PaymentStatus.UNPAID
                && order.getVnpayTxnRef() != null && !order.getVnpayTxnRef().isBlank();
    }

    private void requireAuthenticatedUser(Long userId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
