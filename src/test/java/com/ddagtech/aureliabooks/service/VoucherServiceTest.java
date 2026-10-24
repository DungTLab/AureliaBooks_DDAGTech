package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.VoucherForm;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.VoucherMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.VoucherServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoucherServiceTest {
    private ValidatorFactory factory;
    private VoucherRepository vouchers;
    private OrderVoucherRepository redemptions;
    private UserRepository users;
    private VoucherService service;
    private Voucher voucher;
    private User customer;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 12, 0);

    @BeforeEach
    void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        vouchers = mock(VoucherRepository.class);
        redemptions = mock(OrderVoucherRepository.class);
        users = mock(UserRepository.class);
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new VoucherServiceImpl(vouchers, redemptions, users, mock(OrderRepository.class),
                mock(OrderItemRepository.class), new VoucherMapper(), factory.getValidator(), clock,
                mock(AuditLogService.class), new ObjectMapper(), mock(EntityManager.class));
        customer = User.builder().id(1L).role(Role.builder().roleName("ROLE_CUSTOMER").build()).isActive(true).build();
        when(users.findById(1L)).thenReturn(Optional.of(customer));
        voucher = new Voucher();
        voucher.setId(10L);
        voucher.setCode("SALE10");
        voucher.setScope(Voucher.Scope.PRODUCT);
        voucher.setType(Voucher.Type.PERCENTAGE);
        voucher.setDiscountPercentage(new BigDecimal("10"));
        voucher.setMaxDiscountAmount(new BigDecimal("100000"));
        voucher.setMinOrderValue(BigDecimal.ZERO);
        voucher.setUsageLimit(10);
        voucher.setActive(true);
        voucher.setStartDate(NOW.minusDays(1));
        voucher.setEndDate(NOW.plusDays(1));
        when(vouchers.findByCode("SALE10")).thenReturn(Optional.of(voucher));
    }

    @AfterEach
    void close() {
        factory.close();
    }

    @Test
    void tenPercentOnFiveHundredThousandGivesFiftyThousandWithoutMutation() {
        var quote = service.preview(1L, " sale10 ", new BigDecimal("500000"), BigDecimal.ZERO);
        assertThat(quote.discountAmount()).isEqualByComparingTo("50000");
        assertThat(quote.payableTotal()).isEqualByComparingTo("450000");
        assertThat(voucher.getUsageLimit()).isEqualTo(10);
        verify(vouchers, never()).consumeRemainingUse(anyLong(), any());
        verify(redemptions, never()).saveAndFlush(any());
    }

    @Test
    void fixedFiftyThousandOnThreeHundredThousand() {
        voucher.setType(Voucher.Type.FIXED_AMOUNT);
        voucher.setDiscountAmount(new BigDecimal("50000"));
        var quote = service.preview(1L, "SALE10", new BigDecimal("300000"), BigDecimal.ZERO);
        assertThat(quote.discountAmount()).isEqualByComparingTo("50000");
        assertThat(quote.payableTotal()).isEqualByComparingTo("250000");
    }

    @Test
    void percentIsCappedAndNeverDiscountsShippingForProductScope() {
        voucher.setDiscountPercentage(new BigDecimal("50"));
        voucher.setMaxDiscountAmount(new BigDecimal("30000"));
        var quote = service.preview(1L, "SALE10", new BigDecimal("500000"), new BigDecimal("50000"));
        assertThat(quote.discountAmount()).isEqualByComparingTo("30000");
        assertThat(quote.payableTotal()).isEqualByComparingTo("520000");
    }

    @Test
    void shippingScopeIsLimitedToActualShippingAndNeverMakesTotalNegative() {
        voucher.setScope(Voucher.Scope.SHIPPING);
        voucher.setType(Voucher.Type.FIXED_AMOUNT);
        voucher.setDiscountAmount(new BigDecimal("50000"));
        var quote = service.preview(1L, "SALE10", new BigDecimal("300000"), new BigDecimal("30000"));
        assertThat(quote.discountAmount()).isEqualByComparingTo("30000");
        assertThat(quote.payableTotal()).isEqualByComparingTo("300000");
        assertThatThrownBy(() -> service.preview(1L, "SALE10", new BigDecimal("300000"), BigDecimal.ZERO))
                .isInstanceOf(AppException.class);
    }

    @Test
    void fixedDiscountCannotExceedMerchandiseSubtotal() {
        voucher.setType(Voucher.Type.FIXED_AMOUNT);
        voucher.setDiscountAmount(new BigDecimal("900000"));
        voucher.setMaxDiscountAmount(null);
        assertThat(service.preview(1L, "SALE10", new BigDecimal("300000"), BigDecimal.ZERO).payableTotal())
                .isEqualByComparingTo("0");
    }

    @Test
    void minimumSpendExcludesShippingAndAcceptsExactBoundary() {
        voucher.setMinOrderValue(new BigDecimal("500000"));
        assertThatThrownBy(() -> service.preview(1L, "SALE10", new BigDecimal("499999"), new BigDecimal("50000")))
                .isInstanceOf(AppException.class).hasMessageContaining("Chưa đủ giá trị tối thiểu");
        assertThat(service.preview(1L, "SALE10", new BigDecimal("500000"), BigDecimal.ZERO).discountAmount())
                .isEqualByComparingTo("50000");
    }

    @Test
    void startInclusiveAndEndExclusive() {
        voucher.setStartDate(NOW);
        assertThat(service.preview(1L, "SALE10", new BigDecimal("100000"), BigDecimal.ZERO)).isNotNull();
        voucher.setEndDate(NOW);
        assertThatThrownBy(() -> service.preview(1L, "SALE10", new BigDecimal("100000"), BigDecimal.ZERO))
                .isInstanceOf(AppException.class);
        voucher.setEndDate(NOW.plusDays(1));
        voucher.setStartDate(NOW.plusSeconds(1));
        assertThatThrownBy(() -> service.preview(1L, "SALE10", new BigDecimal("100000"), BigDecimal.ZERO))
                .isInstanceOf(AppException.class);
    }

    @Test
    void exhaustedInactiveAndPreviouslyRedeemedAreRejected() {
        voucher.setUsageLimit(0);
        assertThatThrownBy(() -> service.preview(1L, "SALE10", BigDecimal.TEN, BigDecimal.ZERO))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VOUCHER_QUOTA_EXHAUSTED);
        voucher.setUsageLimit(2);
        voucher.setActive(false);
        assertThatThrownBy(() -> service.preview(1L, "SALE10", BigDecimal.TEN, BigDecimal.ZERO))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VOUCHER_INVALID_OR_EXPIRED);
        voucher.setActive(true);
        when(redemptions.existsByUserIdAndVoucherId(1L, 10L)).thenReturn(true);
        assertThatThrownBy(() -> service.preview(1L, "SALE10", BigDecimal.TEN, BigDecimal.ZERO))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VOUCHER_ALREADY_USED_BY_USER);
    }

    @Test
    void customerCannotManageAndInactiveCustomerCannotPreview() {
        assertThatThrownBy(() -> service.create(1L, form(), "ip"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
        customer.setIsActive(false);
        assertThatThrownBy(() -> service.preview(1L, "SALE10", BigDecimal.TEN, BigDecimal.ZERO))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    void formRejectsPercentageWithoutCapWrongTypeAndInvalidDates() {
        var f = form();
        assertThat(factory.getValidator().validate(f)).isEmpty();
        var invalid = new VoucherForm("bad!", "x", Voucher.Scope.PRODUCT, Voucher.Type.PERCENTAGE,
                new BigDecimal("101"), BigDecimal.ONE, null, BigDecimal.ZERO, NOW, NOW, 0);
        assertThat(factory.getValidator().validate(invalid)).isNotEmpty();
    }

    private VoucherForm form() {
        return new VoucherForm("SALE10", "Giảm 10%", Voucher.Scope.PRODUCT, Voucher.Type.PERCENTAGE,
                BigDecimal.TEN, null, new BigDecimal("50000"), BigDecimal.ZERO,
                NOW, NOW.plusDays(1), 10);
    }
}
