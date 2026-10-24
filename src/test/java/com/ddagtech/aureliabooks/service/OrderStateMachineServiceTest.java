package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.Order.OrderStatus;
import com.ddagtech.aureliabooks.exception.*;
import com.ddagtech.aureliabooks.mapper.OrderMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.OrderStateMachineServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class OrderStateMachineServiceTest {
    @Mock OrderRepository orders;
    @Mock UserRepository users;
    @Mock StockMutationService stock;
    @Mock AuditLogService audit;
    @Mock EntityManager entityManager;
    @Mock OrderAuditRepository auditEntries;
    static ValidatorFactory factory;
    OrderStateMachineServiceImpl service;
    Order order;

    @BeforeAll static void validation() { factory = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void close() { factory.close(); }
    @BeforeEach void setup() {
        service = new OrderStateMachineServiceImpl(orders, users, new OrderMapper(), new OrderStatusValidator(),
                stock, audit, new ObjectMapper(), factory.getValidator(), entityManager, auditEntries);
        order = Order.builder().id(12L).orderCode("code")
                .orderStatus(OrderStatus.PENDING_CONFIRMATION).paymentMethod(Order.PaymentMethod.COD)
                .stockDeductedAt(LocalDateTime.now().minusDays(1)).build();
    }

    void staff() {
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).isActive(true)
                .role(Role.builder().roleName("ROLE_SALE_STAFF").build()).build()));
    }
    void found() { when(orders.findByIdWithLock(12L)).thenReturn(Optional.of(order)); }
    OrderTransitionRequest request(OrderStatus target) { return new OrderTransitionRequest(target, "Khách yêu cầu", "TRACK-123", target == OrderStatus.DELIVERED); }

    static Stream<Arguments> graph() {
        Set<String> edges = Set.of("PENDING_CONFIRMATION:CONFIRMED",
                "PENDING_CONFIRMATION:CANCELLED", "CONFIRMED:SHIPPING", "CONFIRMED:CANCELLED", "SHIPPING:DELIVERED");
        return Arrays.stream(OrderStatus.values()).flatMap(from -> Arrays.stream(OrderStatus.values())
                .map(to -> Arguments.of(from, to, edges.contains(from + ":" + to))));
    }

    @ParameterizedTest @MethodSource("graph")
    void strictGraphCoversEveryPair(OrderStatus from, OrderStatus to, boolean allowed) {
        var validator = new OrderStatusValidator();
        if (allowed) assertThatCode(() -> validator.validateTransition(from, to)).doesNotThrowAnyException();
        else assertThatThrownBy(() -> validator.validateTransition(from, to)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test void nullTargetIsAnIllegalTransition() {
        assertThatThrownBy(() -> new OrderStatusValidator().validateTransition(OrderStatus.CONFIRMED, null))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test void happyPathStampsMilestonesWithoutDeductingAgain() {
        staff(); found();
        service.transitionStatus(12L, request(OrderStatus.CONFIRMED), 7L);
        assertThat(order.getConfirmedAt()).isNotNull();
        service.transitionStatus(12L, request(OrderStatus.SHIPPING), 7L);
        assertThat(order.getShippedAt()).isAfterOrEqualTo(order.getConfirmedAt());
        assertThat(order.getTrackingNumber()).isEqualTo("TRACK-123");
        service.transitionStatus(12L, request(OrderStatus.DELIVERED), 7L);
        assertThat(order.getDeliveredAt()).isAfterOrEqualTo(order.getShippedAt());
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        verifyNoInteractions(stock);
        verify(audit, times(3)).recordRequired(eq(7L), eq("ORDER_STATUS_CHANGED"), eq("orders"), eq(12L), anyString(), isNull());
    }

    @Test void invalidTransitionHasNoWrites() {
        staff(); found();
        assertThatThrownBy(() -> service.transitionStatus(12L, request(OrderStatus.DELIVERED), 7L))
                .isInstanceOf(IllegalStateTransitionException.class);
        verify(orders, never()).flush(); verifyNoInteractions(stock, audit);
    }

    @Test void refreshesCachedStateUnderLockBeforeValidation() {
        staff(); found();
        doAnswer(invocation -> { order.setOrderStatus(OrderStatus.CONFIRMED); return null; })
                .when(entityManager).refresh(order, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> service.transitionStatus(12L, request(OrderStatus.CONFIRMED), 7L))
                .isInstanceOf(IllegalStateTransitionException.class);
        verifyNoInteractions(stock, audit);
    }

    @Test void cancellationRequiresReason() {
        staff(); found();
        assertThatThrownBy(() -> service.transitionStatus(12L, new OrderTransitionRequest(OrderStatus.CANCELLED, "  ", null, null), 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_DATA);
        verifyNoInteractions(stock, audit);
    }

    @Test void cancelLocksBeforeStockAndAuditsReason() {
        staff(); found(); order.setPaymentStatus(Order.PaymentStatus.PAID);
        service.transitionStatus(12L, request(OrderStatus.CANCELLED), 7L);
        assertThat(order.getCancelReason()).isEqualTo("Khách yêu cầu");
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        var sequence = inOrder(orders, entityManager, stock, audit);
        sequence.verify(orders).findByIdWithLock(12L);
        sequence.verify(entityManager).refresh(order, LockModeType.PESSIMISTIC_WRITE);
        sequence.verify(stock).restoreCancelledOrder(12L, 7L);
        sequence.verify(orders).flush();
        sequence.verify(audit).recordRequired(eq(7L), eq("ORDER_STATUS_CHANGED"), eq("orders"), eq(12L), contains("Khách yêu cầu"), isNull());
    }

    @Test void unpaidVnpayCannotConfirm() {
        staff(); found(); order.setPaymentMethod(Order.PaymentMethod.VNPAY);
        assertThatThrownBy(() -> service.transitionStatus(12L, request(OrderStatus.CONFIRMED), 7L)).isInstanceOf(AppException.class);
        verifyNoInteractions(stock, audit);
    }

    @Test void unverifiedVnpayCannotCancel() {
        staff(); found(); order.setPaymentMethod(Order.PaymentMethod.VNPAY); order.setVnpayTxnRef("pending");
        assertThatThrownBy(() -> service.transitionStatus(12L, request(OrderStatus.CANCELLED), 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.ORDER_PAYMENT_OUTCOME_UNVERIFIED);
        verifyNoInteractions(stock, audit);
    }

    @Test void customerCannotUseStaffServiceEvenWithKnownId() {
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().isActive(true)
                .role(Role.builder().roleName("ROLE_CUSTOMER").build()).build()));
        assertThatThrownBy(() -> service.transitionStatus(12L, request(OrderStatus.CONFIRMED), 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(orders, stock, audit);
    }

    @Test void reversedDateRangeIsRejected() {
        staff();
        assertThatThrownBy(() -> service.getOrderQueue(new OrderQueueFilter(null, null, null,
                LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 23)), PageRequest.of(0, 20), 7L))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(orders);
    }

    @Test void confirmReturnRestockSucceedsForShippingOrderAndRetainsStatus() {
        staff(); found();
        order.setOrderStatus(OrderStatus.SHIPPING);
        order.setShippedAt(LocalDateTime.now().minusHours(2));
        order.setPaymentStatus(Order.PaymentStatus.PAID);
        var req = new ReturnConfirmationRequest(true, "Giao không thành công, hoàn kho", "Kiện hàng nguyên vẹn");
        service.confirmReturnRestock(12L, req, 7L);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        verify(stock).restoreReturnedOrder(12L, 7L);
        verify(audit).recordRequired(eq(7L), eq("ORDER_RETURN_INSPECTION_CONFIRMED"), eq("orders"), eq(12L), contains("Giao không thành công"), isNull());
    }

    @Test void confirmReturnRestockSucceedsForDeliveredOrderAndRetainsStatus() {
        staff(); found();
        order.setOrderStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(LocalDateTime.now().minusHours(2));
        order.setPaymentStatus(Order.PaymentStatus.PAID);
        var req = new ReturnConfirmationRequest(true, "Khách đổi ý trả toàn bộ đơn", "Sách mới 100%");
        service.confirmReturnRestock(12L, req, 7L);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        verify(stock).restoreReturnedOrder(12L, 7L);
        verify(audit).recordRequired(eq(7L), eq("ORDER_RETURN_INSPECTION_CONFIRMED"), eq("orders"), eq(12L), contains("Khách đổi ý"), isNull());
    }

    @Test void confirmReturnRestockForbiddenForManager() {
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).isActive(true)
                .role(Role.builder().roleName("ROLE_MANAGER").build()).build()));
        var req = new ReturnConfirmationRequest(true, "Lý do", "Ghi chú");
        assertThatThrownBy(() -> service.confirmReturnRestock(12L, req, 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(stock, audit);
    }

    @Test void confirmReturnRestockRejectsUnverifiedFullReturn() {
        staff();
        var req = new ReturnConfirmationRequest(false, "Lý do", "Ghi chú");
        assertThatThrownBy(() -> service.confirmReturnRestock(12L, req, 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_DATA);
        verifyNoInteractions(stock, audit);
    }

    @Test void confirmReturnRestockRejectsInvalidOrderState() {
        staff(); found();
        order.setOrderStatus(OrderStatus.CONFIRMED);
        var req = new ReturnConfirmationRequest(true, "Lý do", "Ghi chú");
        assertThatThrownBy(() -> service.confirmReturnRestock(12L, req, 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_ORDER_STATE_TRANSITION);
        verifyNoInteractions(stock, audit);
    }

    @Test void confirmReturnRestockRejectsAlreadyRestoredOrder() {
        staff(); found();
        order.setOrderStatus(OrderStatus.SHIPPING);
        order.setStockRestoredAt(LocalDateTime.now().minusHours(1));
        var req = new ReturnConfirmationRequest(true, "Lý do", "Ghi chú");
        assertThatThrownBy(() -> service.confirmReturnRestock(12L, req, 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.ORDER_STOCK_ALREADY_RESTORED);
        verifyNoInteractions(stock, audit);
    }

    @Test void transitionStatusBlocksDeliveredWhenAlreadyRestocked() {
        staff(); found();
        order.setOrderStatus(OrderStatus.SHIPPING);
        order.setStockRestoredAt(LocalDateTime.now().minusHours(1));
        order.setStockRestoreReason(Order.StockRestoreReason.RETURNED);
        assertThatThrownBy(() -> service.transitionStatus(12L, request(OrderStatus.DELIVERED), 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_ORDER_STATE_TRANSITION);
        verifyNoInteractions(stock);
    }
}
