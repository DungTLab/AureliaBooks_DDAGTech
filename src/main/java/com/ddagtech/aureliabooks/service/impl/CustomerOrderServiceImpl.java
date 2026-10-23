package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.CancelOrderRequest;
import com.ddagtech.aureliabooks.dto.response.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.OrderMapper;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.service.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerOrderServiceImpl implements CustomerOrderService {
    private final OrderRepository orders;
    private final OrderMapper mapper;
    private final StockMutationService stock;
    private final AuditLogService audit;
    private final EntityManager entityManager;

    @Override
    public Page<OrderHistoryView> getMyOrders(Long userId, int page, Order.OrderStatus status) {
        requireOwner(userId);
        var pageable = PageRequest.of(Math.max(0, page), 10,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return (status == null ? orders.findByUser_Id(userId, pageable)
                : orders.findByUser_IdAndOrderStatus(userId, status, pageable)).map(mapper::toHistory);
    }

    @Override
    public OrderDetailView getMyOrderDetail(Long userId, String orderCode) {
        requireOwner(userId);
        Order order = orders.findByOrderCodeAndUser_Id(orderCode, userId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        String notice = null;
        if (unverifiedPayment(order)) notice = ErrorCode.ORDER_PAYMENT_OUTCOME_UNVERIFIED.getMessage();
        else if (order.getPaymentStatus() == Order.PaymentStatus.PAID)
            notice = "Đơn đã thanh toán. Hủy đơn không tự động hoàn tiền; vui lòng liên hệ cửa hàng để xử lý hoàn tiền.";
        return new OrderDetailView(mapper.toConfirmation(order), order.getTrackingNumber(),
                order.getCancelReason(), mapper.toTimeline(order), eligible(order) && !unverifiedPayment(order), notice);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelMyOrder(Long userId, String orderCode, CancelOrderRequest request) {
        requireOwner(userId);
        if (request == null || request.reason() == null || request.reason().isBlank()
                || request.reason().length() > 255)
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Lý do hủy phải có từ 1 đến 255 ký tự.");
        Order order = orders.findOwnedWithLock(orderCode, userId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        // A locking query alone does not refresh an entity already present in the persistence context.
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        if (!order.getUser().getId().equals(userId)) throw new AppException(ErrorCode.ORDER_NOT_FOUND);
        if (order.getOrderStatus() == Order.OrderStatus.CANCELLED) return;
        if (!eligible(order)) throw new AppException(ErrorCode.ORDER_NOT_ELIGIBLE_FOR_CANCEL);
        if (unverifiedPayment(order)) throw new AppException(ErrorCode.ORDER_PAYMENT_OUTCOME_UNVERIFIED);
        order.setCancelReason(request.reason().strip());
        // The stock engine joins this transaction and owns cancellation timestamps and stock markers.
        stock.restoreCancelledOrder(order.getId(), userId);
        audit.record(userId, "ORDER_CANCELLED_BY_CUSTOMER", "orders", order.getId(), null, null);
    }

    private boolean eligible(Order order) {
        return order.getShippedAt() == null && order.getDeliveredAt() == null
                && order.getCancelledAt() == null
                && order.getStockRestoredAt() == null
                && switch (order.getOrderStatus()) {
                    case PENDING_PAYMENT, PENDING_CONFIRMATION, CONFIRMED -> true;
                    default -> false;
                };
    }

    private boolean unverifiedPayment(Order order) {
        return order.getPaymentMethod() == Order.PaymentMethod.VNPAY
                && order.getPaymentStatus() == Order.PaymentStatus.UNPAID
                && order.getVnpayTxnRef() != null && !order.getVnpayTxnRef().isBlank();
    }

    private void requireOwner(Long userId) {
        if (userId == null) throw new AppException(ErrorCode.UNAUTHENTICATED);
    }
}
