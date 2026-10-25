package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.CancelOrderRequest;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.OrderMapper;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.service.impl.CustomerOrderServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class CustomerOrderServiceTest {
    @Mock OrderRepository orders;
    @Mock StockMutationService stock;
    @Mock AuditLogService audit;
    @Mock EntityManager em;
    CustomerOrderServiceImpl service;
    Order order;

    @BeforeEach void setup() {
        service = new CustomerOrderServiceImpl(orders, new OrderMapper(), stock, audit, em);
        order = Order.builder().id(12L).orderCode("code").user(User.builder().id(7L).build())
                .paymentMethod(Order.PaymentMethod.COD).orderStatus(Order.OrderStatus.PENDING_CONFIRMATION).build();
        order.setCreatedAt(LocalDateTime.now().minusDays(1));
    }

    @Test void historyUsesOwnerAndStablePagination() {
        when(orders.findByUser_Id(eq(7L), any())).thenReturn(new PageImpl<>(List.of(order)));
        assertThat(service.getMyOrders(7L, -1, null).getContent()).hasSize(1);
        verify(orders).findByUser_Id(7L, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    @ParameterizedTest
    @EnumSource(Order.OrderStatus.class)
    void filtersByOwnerAndStatusBeforePagination(Order.OrderStatus status) {
        when(orders.findByUser_IdAndOrderStatus(eq(7L), eq(status), any())).thenReturn(Page.empty());
        assertThat(service.getMyOrders(7L, 2, status).getContent()).isEmpty();
        verify(orders).findByUser_IdAndOrderStatus(7L, status,
                PageRequest.of(2, 10, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        verify(orders, never()).findByUser_Id(any(), any());
    }

    @Test void anotherOwnersOrderIsNotFound() {
        when(orders.findOwnedWithLock("code", 8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelMyOrder(8L, "code", new CancelOrderRequest("Thay đổi nhu cầu")))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        verifyNoInteractions(stock, audit);
    }

    @ParameterizedTest
    @EnumSource(value = Order.OrderStatus.class, names = {"PENDING_PAYMENT", "PENDING_CONFIRMATION", "CONFIRMED"})
    void cancelsBeforeShipping(Order.OrderStatus status) {
        order.setOrderStatus(status);
        when(orders.findOwnedWithLock("code", 7L)).thenReturn(Optional.of(order));
        service.cancelMyOrder(7L, "code", new CancelOrderRequest("  Thay đổi nhu cầu  "));
        assertThat(order.getCancelReason()).isEqualTo("Thay đổi nhu cầu");
        verify(stock).restoreCancelledOrder(12L, 7L);
        verify(audit).record(7L, "ORDER_CANCELLED_BY_CUSTOMER", "orders", 12L, null, null);
    }

    @ParameterizedTest
    @EnumSource(value = Order.OrderStatus.class, names = {"SHIPPING", "DELIVERED"})
    void refusesShippingAndDeliveredOrders(Order.OrderStatus status) {
        order.setOrderStatus(status);
        when(orders.findOwnedWithLock("code", 7L)).thenReturn(Optional.of(order));
        assertThatThrownBy(() -> service.cancelMyOrder(7L, "code", new CancelOrderRequest("Hủy")))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_ELIGIBLE_FOR_CANCEL));
        verifyNoInteractions(stock, audit);
    }

    @Test void repeatedCancellationDoesNotRestoreAgain() {
        order.setOrderStatus(Order.OrderStatus.CANCELLED);
        when(orders.findOwnedWithLock("code", 7L)).thenReturn(Optional.of(order));
        service.cancelMyOrder(7L, "code", new CancelOrderRequest("Hủy"));
        verifyNoInteractions(stock, audit);
    }

    @Test void unverifiedVnpayCannotCancel() {
        order.setPaymentMethod(Order.PaymentMethod.VNPAY);
        order.setVnpayTxnRef("pending-reference");
        when(orders.findOwnedWithLock("code", 7L)).thenReturn(Optional.of(order));
        assertThatThrownBy(() -> service.cancelMyOrder(7L, "code", new CancelOrderRequest("Hủy")))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_PAYMENT_OUTCOME_UNVERIFIED));
        verifyNoInteractions(stock, audit);
    }

    @Test void paidCancellationDoesNotClaimRefund() {
        order.setPaymentStatus(Order.PaymentStatus.PAID);
        when(orders.findOwnedWithLock("code", 7L)).thenReturn(Optional.of(order));
        service.cancelMyOrder(7L, "code", new CancelOrderRequest("Hủy"));
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        verify(stock).restoreCancelledOrder(12L, 7L);
    }

    @Test void cancelledTimelinePreservesOnlyRecordedMilestones() {
        order.setOrderStatus(Order.OrderStatus.CANCELLED);
        order.setCancelledAt(LocalDateTime.now());
        assertThat(new OrderMapper().toTimeline(order)).extracting("label").containsExactly("Đã đặt hàng", "Đã hủy");
    }

    @Test void stockFailurePropagatesWithoutSuccessAudit() {
        when(orders.findOwnedWithLock("code", 7L)).thenReturn(Optional.of(order));
        doThrow(new IllegalStateException("stock failure")).when(stock).restoreCancelledOrder(12L, 7L);
        assertThatThrownBy(() -> service.cancelMyOrder(7L, "code", new CancelOrderRequest("Hủy")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audit);
    }
}
