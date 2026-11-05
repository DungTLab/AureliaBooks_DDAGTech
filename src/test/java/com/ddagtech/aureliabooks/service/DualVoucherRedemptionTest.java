package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.VoucherMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.VoucherServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises redemption decisions; database rollback and locking are covered by opt-in MySQL tests. */
class DualVoucherRedemptionTest {
    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final OrderVoucherRepository redemptions = mock(OrderVoucherRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository items = mock(OrderItemRepository.class);
    private final List<OrderVoucher> saved = new ArrayList<>();
    private ValidatorFactory validation;
    private VoucherService service;
    private Order order;
    private Voucher product;
    private Voucher shipping;

    @BeforeEach void setup() {
        validation = Validation.buildDefaultValidatorFactory();
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new VoucherServiceImpl(vouchers, redemptions, users, orders, items, new VoucherMapper(),
            validation.getValidator(), clock, mock(AuditLogService.class), new ObjectMapper(), mock(EntityManager.class));
        User owner = User.builder().id(1L).isActive(true).role(Role.builder().roleName("ROLE_CUSTOMER").build()).build();
        order = Order.builder().id(2L).user(owner).shippingFee(new BigDecimal("30000"))
            .finalTotalAmount(new BigDecimal("530000")).paymentStatus(Order.PaymentStatus.UNPAID)
            .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION).build();
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        when(orders.findByIdWithLock(2L)).thenReturn(Optional.of(order));
        when(items.findByOrderId(2L)).thenReturn(List.of(OrderItem.builder().quantity(1)
            .unitPriceSnapshot(new BigDecimal("500000")).build()));
        product = campaign(10L, "SAVE10", Voucher.Scope.PRODUCT, "50000");
        shipping = campaign(11L, "SHIP20", Voucher.Scope.SHIPPING, "20000");
        when(redemptions.findByOrderIdAndScope(eq(2L), any())).thenAnswer(call -> saved.stream()
            .filter(v -> v.getScope() == call.getArgument(1)).findFirst());
        when(redemptions.findAllByOrderId(2L)).thenAnswer(call -> List.copyOf(saved));
        when(redemptions.findByOrderIdForUpdate(2L)).thenAnswer(call -> List.copyOf(saved));
        when(redemptions.saveAndFlush(any())).thenAnswer(call -> {
            OrderVoucher value = call.getArgument(0); saved.add(value); return value;
        });
        doAnswer(call -> { saved.remove(call.getArgument(0)); return null; }).when(redemptions).delete(any());
    }

    @AfterEach void close() { validation.close(); }

    private Voucher campaign(long id, String code, Voucher.Scope scope, String amount) {
        Voucher voucher = new Voucher();
        voucher.setId(id); voucher.setCode(code); voucher.setScope(scope); voucher.setActive(true);
        voucher.setType(Voucher.Type.FIXED_AMOUNT); voucher.setDiscountAmount(new BigDecimal(amount));
        voucher.setMinOrderValue(BigDecimal.ZERO); voucher.setUsageLimit(1);
        voucher.setStartDate(LocalDateTime.of(2026, 11, 3, 0, 0));
        voucher.setEndDate(LocalDateTime.of(2026, 11, 5, 0, 0));
        when(vouchers.findByCodeForUpdate(code)).thenReturn(Optional.of(voucher));
        when(vouchers.findByIdForUpdate(id)).thenReturn(Optional.of(voucher));
        when(vouchers.consumeRemainingUse(eq(id), any())).thenReturn(1);
        return voucher;
    }

    @Test void twoScopesReduceTheTotalAndReplayDoesNotConsumeAgain() {
        service.redeem(1L, 2L, "SAVE10", "ip");
        service.redeem(1L, 2L, "SHIP20", "ip");
        assertThat(saved).extracting(OrderVoucher::getScope).containsExactly(Voucher.Scope.PRODUCT, Voucher.Scope.SHIPPING);
        assertThat(order.getFinalTotalAmount()).isEqualByComparingTo("460000");
        service.redeem(1L, 2L, "SAVE10", "ip");
        service.redeem(1L, 2L, "SHIP20", "ip");
        verify(vouchers, times(1)).consumeRemainingUse(eq(10L), any());
        verify(vouchers, times(1)).consumeRemainingUse(eq(11L), any());
        assertThat(saved).hasSize(2);
    }

    @Test void secondCodeInTheSameScopeIsRejected() {
        service.redeem(1L, 2L, "SAVE10", "ip");
        campaign(12L, "SAVE20", Voucher.Scope.PRODUCT, "20000");
        assertThatThrownBy(() -> service.redeem(1L, 2L, "SAVE20", "ip")).isInstanceOf(AppException.class);
        verify(vouchers, never()).consumeRemainingUse(eq(12L), any());
    }

    @Test void cancellationRestoresBothScopesOnceAndKeepsTheMonetarySnapshot() {
        service.redeem(1L, 2L, "SAVE10", "ip");
        service.redeem(1L, 2L, "SHIP20", "ip");
        order.setOrderStatus(Order.OrderStatus.CANCELLED);
        service.releaseCancelledOrder(2L, 1L, "ip");
        service.releaseCancelledOrder(2L, 1L, "ip");
        assertThat(saved).isEmpty();
        assertThat(order.getFinalTotalAmount()).isEqualByComparingTo("460000");
        verify(vouchers, times(1)).restoreRemainingUse(10L);
        verify(vouchers, times(1)).restoreRemainingUse(11L);
    }

    @Test void exhaustedSecondQuotaPropagatesToThePlacementTransaction() {
        when(vouchers.consumeRemainingUse(eq(11L), any())).thenReturn(0);
        service.redeem(1L, 2L, "SAVE10", "ip");
        assertThatThrownBy(() -> service.redeem(1L, 2L, "SHIP20", "ip"))
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VOUCHER_QUOTA_EXHAUSTED);
        assertThat(saved).hasSize(1); // Mocks do not roll back; the MySQL test verifies transaction atomicity.
    }
}
