package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.entity.AuditLog;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.OrderMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.OrderStateMachineServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class OrderRefundServiceTest {
    @Mock OrderRepository orders;
    @Mock UserRepository users;
    @Mock StockMutationService stock;
    @Mock AuditLogService audit;
    @Mock EntityManager em;
    @Mock OrderAuditRepository ledger;
    static ValidatorFactory factory;
    OrderStateMachineServiceImpl service;
    User actor;
    Order order;

    @BeforeAll static void validation() { factory = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void close() { factory.close(); }
    @BeforeEach void setup() {
        service = new OrderStateMachineServiceImpl(orders, users, new OrderMapper(), new OrderStatusValidator(),
                stock, audit, new ObjectMapper(), factory.getValidator(), em, ledger);
        actor = User.builder().id(7L).fullName("Staff").role(Role.builder().roleName("ROLE_SALE_STAFF").build()).build();
        when(users.findById(7L)).thenReturn(Optional.of(actor));
        order = Order.builder().id(12L).user(User.builder().id(8L).fullName("Buyer").build()).orderCode("code")
                .orderStatus(Order.OrderStatus.CANCELLED).paymentMethod(Order.PaymentMethod.VNPAY)
                .paymentStatus(Order.PaymentStatus.PAID).finalTotalAmount(new BigDecimal("100000"))
                .stockDeductedAt(LocalDateTime.now().minusDays(1)).build();
    }
    void found() { when(orders.findByIdWithLock(12L)).thenReturn(Optional.of(order)); }
    RefundConfirmationRequest refund() { return new RefundConfirmationRequest(new BigDecimal("100000"),
            "Chuyển khoản", LocalDate.now(), "Biên nhận hoàn tiền", true); }

    @Test void fullRefundRecordsEvidenceWithoutChangingOrderOrStock() {
        found();
        service.confirmExternalRefund(12L, refund(), 7L);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.REFUNDED);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        assertThat(order.getStockRestoredAt()).isNull();
        verifyNoInteractions(stock);
        verify(audit).recordRequired(eq(7L), eq("ORDER_REFUND_CONFIRMED"), eq("orders"), eq(12L),
                argThat(payload -> payload.contains("Biên nhận hoàn tiền") && payload.contains("100000") && payload.contains("completedDate")), isNull());
    }

    @Test void receivedReturnIsARefundBasisWithoutAnotherRestock() {
        found(); order.setOrderStatus(Order.OrderStatus.RETURNED);
        order.setStockRestoreReason(Order.StockRestoreReason.RETURNED);
        order.setStockRestoredAt(LocalDateTime.now()); order.setStockRestoredBy(actor);
        service.confirmExternalRefund(12L, refund(), 7L);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.RETURNED);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.REFUNDED);
        verifyNoInteractions(stock);
    }

    @Test void shippingOrderCannotRefundEvenWithLegacyReturnMarkers() {
        found(); order.setOrderStatus(Order.OrderStatus.SHIPPING);
        order.setStockRestoreReason(Order.StockRestoreReason.RETURNED);
        order.setStockRestoredAt(LocalDateTime.now()); order.setStockRestoredBy(actor);
        assertThatThrownBy(() -> service.confirmExternalRefund(12L, refund(), 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.ORDER_NOT_ELIGIBLE_FOR_REFUND);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.SHIPPING);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        verifyNoInteractions(stock);
    }

    @Test void refundNeedsAnAcceptedBasis() {
        found(); order.setOrderStatus(Order.OrderStatus.DELIVERED);
        assertThatThrownBy(() -> service.confirmExternalRefund(12L, refund(), 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.ORDER_NOT_ELIGIBLE_FOR_REFUND);
        verifyNoInteractions(stock, audit);
    }

    @Test void zeroValueOrderHasNoRefundableAmount() {
        found(); order.setFinalTotalAmount(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.confirmExternalRefund(12L, refund(), 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.ORDER_NOT_ELIGIBLE_FOR_REFUND);
        verifyNoInteractions(stock, audit);
    }

    @ParameterizedTest @EnumSource(value=Order.PaymentStatus.class, names={"UNPAID", "REFUNDED"})
    void unpaidOrAlreadyRefundedCannotRefund(Order.PaymentStatus status) {
        found(); order.setPaymentStatus(status);
        assertThatThrownBy(() -> service.confirmExternalRefund(12L, refund(), 7L)).isInstanceOf(AppException.class);
        verifyNoInteractions(stock, audit);
    }

    @Test void anExistingConfirmationPreventsDuplicateEvenIfPaymentStatusIsInconsistent() {
        found(); when(ledger.findByTargetTableAndTargetIdAndActionInOrderByCreatedAtAscIdAsc(any(), any(), any()))
                .thenReturn(List.of(new AuditLog()));
        assertThatThrownBy(() -> service.confirmExternalRefund(12L, refund(), 7L)).isInstanceOf(AppException.class);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        verifyNoInteractions(stock, audit);
    }

    static Stream<RefundConfirmationRequest> invalidRefunds() {
        return Stream.of(
                new RefundConfirmationRequest(BigDecimal.ZERO, "Tiền mặt", LocalDate.now(), "Biên nhận hoàn tiền", true),
                new RefundConfirmationRequest(new BigDecimal("100000"), " ", LocalDate.now(), "Biên nhận hoàn tiền", true),
                new RefundConfirmationRequest(new BigDecimal("100000"), "Tiền mặt", LocalDate.now().plusDays(1), "Biên nhận hoàn tiền", true),
                new RefundConfirmationRequest(new BigDecimal("100000"), "Tiền mặt", LocalDate.now(), " ", true),
                new RefundConfirmationRequest(new BigDecimal("100000"), "Tiền mặt", LocalDate.now(), "Biên nhận hoàn tiền", false));
    }

    @ParameterizedTest @MethodSource("invalidRefunds")
    void invalidEvidenceNeverChangesPayment(RefundConfirmationRequest request) {
        assertThatThrownBy(() -> service.confirmExternalRefund(12L, request, 7L)).isInstanceOf(AppException.class);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        verifyNoInteractions(stock, audit);
    }

    @ParameterizedTest @ValueSource(strings={"99999", "100001"})
    void partialOrExcessRefundIsRejected(String amount) {
        found(); var request = new RefundConfirmationRequest(new BigDecimal(amount), "Tiền mặt", LocalDate.now(), "Biên nhận hoàn tiền", true);
        assertThatThrownBy(() -> service.confirmExternalRefund(12L, request, 7L)).isInstanceOf(AppException.class);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        verifyNoInteractions(stock, audit);
    }

    @Test void codCannotDeliverWithoutCollectionConfirmation() {
        found(); order.setOrderStatus(Order.OrderStatus.SHIPPING); order.setPaymentMethod(Order.PaymentMethod.COD);
        order.setPaymentStatus(Order.PaymentStatus.UNPAID);
        assertThatThrownBy(() -> service.transitionStatus(12L,
                new OrderTransitionRequest(Order.OrderStatus.DELIVERED, null, null, false), 7L)).isInstanceOf(AppException.class);
        assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.SHIPPING);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.UNPAID);
        assertThat(order.getDeliveredAt()).isNull(); verifyNoInteractions(stock, audit);
    }

    @ParameterizedTest @EnumSource(value=Order.OrderStatus.class, names={"CONFIRMED", "SHIPPING", "DELIVERED"})
    void managerCannotUseStaffProgression(Order.OrderStatus target) {
        found(); actor.getRole().setRoleName("ROLE_MANAGER");
        assertThatThrownBy(() -> service.transitionStatus(12L, new OrderTransitionRequest(target, null, null, true), 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(stock, audit);
    }

    @Test void managerCannotConfirmRefund() {
        actor.getRole().setRoleName("ROLE_MANAGER");
        assertThatThrownBy(() -> service.confirmExternalRefund(12L, refund(), 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(stock, audit, orders);
    }

    @Test void managerCanCancelAnEligibleOrder() {
        found(); actor.getRole().setRoleName("ROLE_MANAGER"); order.setOrderStatus(Order.OrderStatus.CONFIRMED);
        service.transitionStatus(12L, new OrderTransitionRequest(Order.OrderStatus.CANCELLED, "Khách yêu cầu", null, null), 7L);
        verify(stock).restoreCancelledOrder(12L, 7L);
    }

    @Test void operationalDetailDoesNotExposePendingPaymentOrder() {
        order.setOrderStatus(Order.OrderStatus.PENDING_PAYMENT);
        when(orders.findById(12L)).thenReturn(Optional.of(order));
        assertThatThrownBy(() -> service.getPackagingChecklist(12L, 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test void unpaidVnpayWithoutReferenceIsStillUnresolved() {
        found(); order.setOrderStatus(Order.OrderStatus.CONFIRMED); order.setPaymentStatus(Order.PaymentStatus.UNPAID);
        assertThatThrownBy(() -> service.transitionStatus(12L,
                new OrderTransitionRequest(Order.OrderStatus.CANCELLED, "Hủy", null, null), 7L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.ORDER_PAYMENT_OUTCOME_UNVERIFIED);
        verifyNoInteractions(stock, audit);
    }
}
