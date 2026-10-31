package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.dto.response.VoucherQuote;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.CartMapper;
import com.ddagtech.aureliabooks.mapper.OrderMapper;
import com.ddagtech.aureliabooks.mapper.ShippingAddressMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.OrderPlacementServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit-test business decisions with repository mocks; these tests do not establish SQL rollback behavior. */
@ExtendWith(MockitoExtension.class)
class OrderPlacementServiceTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static ValidatorFactory validation;
    @Mock CartService cartService;
    @Mock AddressService addressService;
    @Mock UserRepository users;
    @Mock OrderRepository orders;
    @Mock CartRepository carts;
    @Mock StockMutationService stockMutations;
    @Mock ShippingAddressRepository addresses;
    @Mock EntityManager entities;
    @Mock AuditLogService audit;
    @Mock VoucherService vouchers;
    @Mock OrderVoucherRepository orderVouchers;

    private OrderPlacementService service;
    private User owner;
    private ShippingAddress address;
    private Product product;
    private Cart cart;
    private CheckoutRequest request;
    private Order savedOrder;
    private final CartMapper cartMapper = new CartMapper();
    private final ShippingAddressMapper addressMapper = new ShippingAddressMapper();

    @BeforeAll static void openValidation() { validation = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void closeValidation() { validation.close(); }

    @BeforeEach void setUp() {
        service = new OrderPlacementServiceImpl(cartService, addressService, validation.getValidator(),
                users, orders, carts, stockMutations, addresses, entities, cartMapper, addressMapper,
                new OrderMapper(), audit, new ObjectMapper(), vouchers, orderVouchers);
        owner = User.builder().id(1L).role(Role.builder().roleName("ROLE_CUSTOMER").build())
                .fullName("Khách hàng").phone("0912345678").phoneVerified(true).build();
        address = new ShippingAddress();
        address.setId(10L); address.setUser(owner); address.setRecipientName("Người nhận");
        address.setPhone("0987654321"); address.setEconomicRegion(ShippingAddress.EconomicRegion.NORTHERN);
        address.setProvince("Hà Nội"); address.setDistrict("Ba Đình"); address.setWard("Đội Cấn");
        address.setDetailedAddress("12 Đội Cấn"); address.setIsDefault(true);
        product = Product.builder().id(20L).title("Sách A").price(new BigDecimal("100000.00"))
                .stockQuantity(5).build();
        cart = new Cart(); cart.setId(30L); cart.setUser(owner);
        CartItem item = new CartItem(); item.setId(40L); item.setProduct(product); item.setQuantity(2);
        cart.addItem(item);
        request = new CheckoutRequest(); request.setCheckoutToken(TOKEN);
        request.setShippingAddressId(address.getId()); request.setPaymentMethod(Order.PaymentMethod.COD);
        lenient().when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
        lenient().when(users.findOwnerForUpdate(owner.getId())).thenReturn(Optional.of(owner));
        lenient().when(carts.findByUser_Id(owner.getId())).thenReturn(Optional.of(cart));
        lenient().when(addresses.findByIdAndUserIdAndIsActiveTrue(address.getId(), owner.getId()))
                .thenReturn(Optional.of(address));
        lenient().when(orders.saveAndFlush(any(Order.class))).thenAnswer(call -> {
            savedOrder = call.getArgument(0);
            savedOrder.setId(100L);
            return savedOrder;
        });
        // The mock represents the engine contract: stamp the marker only after stock deduction succeeds.
        lenient().doAnswer(call -> {
            savedOrder.setStockDeductedAt(LocalDateTime.now());
            return null;
        }).when(stockMutations).deductOrder(100L);
        lenient().when(cartService.viewCart(owner.getId()))
                .thenAnswer(call -> cartMapper.toCartView(cart.getItems()));
        lenient().when(addressService.list(owner.getId()))
                .thenAnswer(call -> List.of(addressMapper.toView(address)));
    }

    private CheckoutConfirmation confirmation() {
        return new CheckoutConfirmation(TOKEN, owner.getId(),
                service.getCheckoutView(owner.getId(), address.getId()), request.getPaymentMethod(), null);
    }

    @Test
    void missingOrBlankAccountPhoneCanStartCheckout() {
        owner.setPhone(null);
        owner.setPhoneVerified(false);
        assertThat(service.getCheckoutView(owner.getId(), address.getId()).selectedAddressId()).isEqualTo(address.getId());
        owner.setPhone("   ");
        assertThat(service.getCheckoutView(owner.getId(), address.getId()).selectedAddressId()).isEqualTo(address.getId());
    }

    @ParameterizedTest @EnumSource(Order.PaymentMethod.class)
    void placementUsesDeliveryPhoneWhenAccountPhoneIsRemovedAfterPreview(Order.PaymentMethod method) {
        request.setPaymentMethod(method);
        CheckoutConfirmation reviewed = confirmation();
        owner.setPhone(null);
        owner.setPhoneVerified(false);
        assertThat(service.placeOrder(owner.getId(), request, reviewed)).isEqualTo(TOKEN);
        assertThat(savedOrder.getShippingPhone()).isEqualTo(address.getPhone());
    }

    @ParameterizedTest @EnumSource(Order.PaymentMethod.class)
    void existingPhoneWithoutVerificationFlagCanCheckoutAndPlaceOrder(Order.PaymentMethod method) {
        owner.setPhoneVerified(false);
        request.setPaymentMethod(method);
        assertThat(service.getCheckoutView(owner.getId(), address.getId()).selectedAddressId())
                .isEqualTo(address.getId());
        assertThat(service.placeOrder(owner.getId(), request, confirmation())).isEqualTo(TOKEN);
        assertThat(owner.isPhoneVerified()).isFalse();
        assertThat(savedOrder.getShippingPhone()).isEqualTo(address.getPhone());
        verify(stockMutations).deductOrder(100L);
    }

    /** Apply the same snapshot and single-deduction expectations to both COD and VNPay. */
    @ParameterizedTest @EnumSource(Order.PaymentMethod.class)
    void placesBothMethodsWithSnapshotsAndOneDeduction(Order.PaymentMethod method) {
        request.setPaymentMethod(method);
        assertThat(service.placeOrder(owner.getId(), request, confirmation())).isEqualTo(TOKEN);
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orders).saveAndFlush(saved.capture());
        Order order = saved.getValue();
        assertThat(order.getOrderStatus()).isEqualTo(method == Order.PaymentMethod.COD
                ? Order.OrderStatus.PENDING_CONFIRMATION : Order.OrderStatus.PENDING_PAYMENT);
        assertThat(order.getPaymentStatus()).isEqualTo(Order.PaymentStatus.UNPAID);
        assertThat(order.getStockDeductedAt()).isNotNull();
        assertThat(order.getShippingFee()).isEqualByComparingTo("50000");
        assertThat(order.getFinalTotalAmount()).isEqualByComparingTo("250000");
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getItems().getFirst().getOrder()).isSameAs(order);
        product.setTitle("Tên đã sửa"); product.setPrice(BigDecimal.ONE);
        address.setDetailedAddress("Địa chỉ đã sửa");
        assertThat(order.getItems().getFirst().getProductNameSnapshot()).isEqualTo("Sách A");
        assertThat(order.getItems().getFirst().getUnitPriceSnapshot()).isEqualByComparingTo("100000");
        assertThat(order.getShippingFullAddress()).isEqualTo("12 Đội Cấn, Đội Cấn, Ba Đình, Hà Nội");
        assertThat(cart.getItems()).isEmpty();
        verify(stockMutations).deductOrder(100L);
        verify(entities).refresh(product, LockModeType.PESSIMISTIC_WRITE);
        verify(audit).record(eq(1L), eq("ORDER_PLACED"), eq("orders"), eq(100L), anyString(), isNull());
    }

    @ParameterizedTest @EnumSource(ShippingAddress.EconomicRegion.class)
    void calculatesThreeRegionalFees(ShippingAddress.EconomicRegion region) {
        address.setEconomicRegion(region);
        String fee = switch (region) {
            case NORTHERN -> "50000";
            case CENTRAL_HIGHLANDS -> "40000";
            case SOUTHERN_MEKONG -> "30000";
        };
        CheckoutView view = service.getCheckoutView(1L, null);
        assertThat(view.shippingFee()).isEqualByComparingTo(fee);
        verifyNoInteractions(stockMutations, orders);
    }

    @ParameterizedTest
    @CsvSource({
        "NORTHERN,349999.99,50000", "NORTHERN,350000,0", "NORTHERN,350000.01,0",
        "CENTRAL_HIGHLANDS,349999.99,40000", "CENTRAL_HIGHLANDS,350000,0", "CENTRAL_HIGHLANDS,350000.01,0",
        "SOUTHERN_MEKONG,249999.99,30000", "SOUTHERN_MEKONG,250000,0", "SOUTHERN_MEKONG,250000.01,0"
    })
    void appliesRegionalFreeShippingBoundaries(ShippingAddress.EconomicRegion region, String subtotal, String fee) {
        address.setEconomicRegion(region);
        cart.getItems().getFirst().setQuantity(1);
        product.setPrice(new BigDecimal(subtotal));
        CheckoutView view = service.getCheckoutView(1L, 10L);
        assertThat(view.shippingFee()).isEqualByComparingTo(fee);
        assertThat(view.finalTotalAmount()).isEqualByComparingTo(new BigDecimal(subtotal).add(new BigDecimal(fee)));
    }

    @ParameterizedTest @EnumSource(Order.PaymentMethod.class)
    void bothMethodsPersistFreeShippingAndUseDeliveryPhoneWithoutAccountPhone(Order.PaymentMethod method) {
        request.setPaymentMethod(method);
        owner.setPhone(null);
        product.setPrice(new BigDecimal("175000"));
        assertThat(service.placeOrder(1L, request, confirmation())).isEqualTo(TOKEN);
        assertThat(savedOrder.getShippingFee()).isZero();
        assertThat(savedOrder.getFinalTotalAmount()).isEqualByComparingTo("350000");
        assertThat(savedOrder.getShippingPhone()).isEqualTo(address.getPhone());
        verify(stockMutations).deductOrder(100L);
    }

    @ParameterizedTest
    @CsvSource({
        "NORTHERN,360000,20000,50000,390000", "NORTHERN,370000,20000,0,350000",
        "CENTRAL_HIGHLANDS,360000,20000,40000,380000", "CENTRAL_HIGHLANDS,370000,20000,0,350000",
        "SOUTHERN_MEKONG,260000,20000,30000,270000", "SOUTHERN_MEKONG,270000,20000,0,250000"
    })
    void productVoucherUsesNetMerchandiseToCalculateAndPersistShipping(
            ShippingAddress.EconomicRegion region, String subtotal, String discount, String fee, String total) {
        address.setEconomicRegion(region);
        cart.getItems().getFirst().setQuantity(1);
        product.setPrice(new BigDecimal(subtotal));
        request.setVoucherCode("PRODUCT20");
        // The initial preview is free; the product discount can put the order back below the threshold.
        when(vouchers.preview(1L, "PRODUCT20", new BigDecimal(subtotal), BigDecimal.ZERO))
                .thenReturn(new VoucherQuote(9L, "PRODUCT20", Voucher.Scope.PRODUCT, new BigDecimal(subtotal),
                        BigDecimal.ZERO, new BigDecimal(discount), new BigDecimal(subtotal).subtract(new BigDecimal(discount))));
        CheckoutView view = service.getCheckoutView(1L, 10L, "PRODUCT20");
        when(vouchers.redeem(1L, 100L, "PRODUCT20", null))
                .thenReturn(new VoucherQuote(9L, "PRODUCT20", Voucher.Scope.PRODUCT, new BigDecimal(subtotal),
                        new BigDecimal(fee), new BigDecimal(discount), new BigDecimal(total)));
        var review = new CheckoutConfirmation(TOKEN, 1L, view, Order.PaymentMethod.COD, "PRODUCT20");

        assertThat(view.shippingFee()).isEqualByComparingTo(fee);
        assertThat(view.finalTotalAmount()).isEqualByComparingTo(total);
        assertThat(service.placeOrder(1L, request, review)).isEqualTo(TOKEN);
        assertThat(savedOrder.getShippingFee()).isEqualByComparingTo(fee);
        assertThat(savedOrder.getFinalTotalAmount()).isEqualByComparingTo(total);
    }

    @Test void shippingVoucherKeepsTheRegionalFeeAsItsRedemptionBasis() {
        request.setVoucherCode("SHIP20");
        var quote = new VoucherQuote(9L, "SHIP20", Voucher.Scope.SHIPPING, new BigDecimal("200000"),
                new BigDecimal("50000"), new BigDecimal("20000"), new BigDecimal("230000"));
        when(vouchers.preview(eq(1L), eq("SHIP20"), any(), any())).thenReturn(quote);
        when(vouchers.redeem(1L, 100L, "SHIP20", null)).thenReturn(quote);
        CheckoutView view = service.getCheckoutView(1L, 10L, "SHIP20");
        assertThat(view.shippingFee()).isEqualByComparingTo("30000");
        service.placeOrder(1L, request, new CheckoutConfirmation(TOKEN, 1L, view, Order.PaymentMethod.COD, "SHIP20"));
        assertThat(savedOrder.getShippingFee()).isEqualByComparingTo("50000");
        assertThat(savedOrder.getFinalTotalAmount()).isEqualByComparingTo("230000");
    }

    @Test void freeShippingPassesZeroToShippingVoucherValidationWithoutRedeeming() {
        product.setPrice(new BigDecimal("175000"));
        when(vouchers.preview(eq(1L), eq("SHIP20"), any(), eq(BigDecimal.ZERO)))
                .thenThrow(new AppException(ErrorCode.INVALID_INPUT_DATA, "Đơn hàng không còn phí vận chuyển để áp dụng mã này."));
        assertThatThrownBy(() -> service.getCheckoutView(1L, 10L, "SHIP20")).isInstanceOf(AppException.class);
        verify(vouchers, never()).redeem(any(), any(), any(), any());
        verifyNoInteractions(stockMutations);
    }

    @Test void supportsPreviewWithoutAddress() {
        when(addressService.list(1L)).thenReturn(List.of());
        CheckoutView view = service.getCheckoutView(1L, null);
        assertThat(view.hasAddresses()).isFalse();
        assertThat(view.shippingFee()).isNull();
        assertThat(view.finalTotalAmount()).isNull();
    }

    @Test void rejectsPriceChangesBeforeCreatingAnything() {
        CheckoutConfirmation reviewed = confirmation();
        product.setPrice(new BigDecimal("110000"));
        assertError(ErrorCode.CHECKOUT_CHANGED, reviewed);
        verifyNoInteractions(stockMutations);
        verify(orders, never()).saveAndFlush(any());
    }

    @Test void rejectsAddressChangesEvenWhenFeeIsUnchanged() {
        CheckoutConfirmation reviewed = confirmation();
        address.setDetailedAddress("Địa chỉ mới");
        assertError(ErrorCode.CHECKOUT_CHANGED, reviewed);
        verify(orders, never()).saveAndFlush(any());
    }

    @Test void ignoresDecimalScaleAndDefaultFlagChanges() {
        CheckoutConfirmation reviewed = confirmation();
        product.setPrice(new BigDecimal("100000"));
        address.setIsDefault(false);
        assertThat(service.placeOrder(1L, request, reviewed)).isEqualTo(TOKEN);
    }

    @Test void rejectsQuantityChangesAndUnavailableProducts() {
        CheckoutConfirmation reviewed = confirmation();
        cart.getItems().getFirst().setQuantity(3);
        assertError(ErrorCode.CHECKOUT_CHANGED, reviewed);
        product.setIsActive(false);
        assertError(ErrorCode.INVALID_INPUT_DATA, reviewed);
        verify(orders, never()).saveAndFlush(any());
    }

    @ParameterizedTest @ValueSource(ints = {0, -1})
    void rejectsInvalidQuantities(int quantity) {
        CheckoutConfirmation reviewed = confirmation();
        cart.getItems().getFirst().setQuantity(quantity);
        assertError(ErrorCode.INVALID_INPUT_DATA, reviewed);
    }

    @Test void rejectsLegacyQuantityAbove10AtPreviewAndPlacement() {
        CheckoutConfirmation reviewed = confirmation();
        cart.getItems().getFirst().setQuantity(11);
        assertThatThrownBy(() -> service.getCheckoutView(1L, 10L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.CART_ITEM_QUANTITY_EXCEEDED);
        assertError(ErrorCode.CART_ITEM_QUANTITY_EXCEEDED, reviewed);
        verify(orders, never()).saveAndFlush(any());
    }

    @Test void allows20ProductsButRejects21AtPreviewAndPlacement() {
        for (long id = 21; id <= 39; id++) {
            CartItem item = new CartItem();
            item.setProduct(Product.builder().id(id).title("Sách " + id).price(BigDecimal.ONE).stockQuantity(5).build());
            item.setQuantity(1);
            cart.addItem(item);
        }
        CheckoutConfirmation reviewed = confirmation();
        assertThat(reviewed.reviewedCheckout().items()).hasSize(20);
        CartItem extra = new CartItem();
        extra.setProduct(Product.builder().id(40L).title("Sách 21").price(BigDecimal.ONE).stockQuantity(5).build());
        extra.setQuantity(1);
        cart.addItem(extra);
        assertThatThrownBy(() -> service.getCheckoutView(1L, 10L))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.CART_LINE_LIMIT_EXCEEDED);
        assertError(ErrorCode.CART_LINE_LIMIT_EXCEEDED, reviewed);
        verify(orders, never()).saveAndFlush(any());
        cart.removeItem(extra);
        assertThat(service.placeOrder(1L, request, reviewed)).isEqualTo(TOKEN);
        assertThat(savedOrder.getItems()).hasSize(20);
    }

    @Test void rejectsInsufficientStockBeforeSaving() {
        CheckoutConfirmation reviewed = confirmation();
        product.setStockQuantity(1);
        assertError(ErrorCode.INSUFFICIENT_STOCK, reviewed);
        verify(orders, never()).saveAndFlush(any());
    }

    @ParameterizedTest @EnumSource(Order.PaymentMethod.class)
    void usesRecipientPhoneIndependentlyOfVerifiedAccountPhone(Order.PaymentMethod method) {
        request.setPaymentMethod(method);
        assertThat(service.placeOrder(1L, request, confirmation())).isEqualTo(TOKEN);
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orders).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getShippingPhone()).isEqualTo(address.getPhone());
        assertThat(saved.getValue().getShippingPhone()).isNotEqualTo(owner.getPhone());
    }

    @ParameterizedTest @ValueSource(strings = {"", "123", "abcdefghij"})
    void rejectsInvalidDeliveryPhone(String phone) {
        address.setPhone(phone);
        assertError(ErrorCode.INVALID_INPUT_DATA, confirmation());
        verify(orders, never()).saveAndFlush(any());
        verifyNoInteractions(stockMutations);
    }

    @Test void rejectsUnreviewedVoucherSelection() {
        request.setVoucherCode("SALE10");
        assertError(ErrorCode.CHECKOUT_CHANGED, null);
        verifyNoInteractions(stockMutations, vouchers);
    }

    @Test void rejectsForeignAddressAndMismatchedConfirmation() {
        CheckoutConfirmation reviewed = confirmation();
        when(addresses.findByIdAndUserIdAndIsActiveTrue(10L, 1L)).thenReturn(Optional.empty());
        assertError(ErrorCode.ADDRESS_NOT_FOUND, reviewed);
        assertError(ErrorCode.CHECKOUT_CHANGED, new CheckoutConfirmation(TOKEN, 2L,
                reviewed.reviewedCheckout(), Order.PaymentMethod.COD, null));
    }

    /** Replay after review removal must return the committed order and preserve the new cart. */
    @Test void replaysCommittedOrderWithoutTouchingNewCartOrRequiringSession() {
        Order existing = Order.builder().orderCode(TOKEN).user(owner).shippingAddress(address)
                .paymentMethod(Order.PaymentMethod.COD).build();
        when(orders.findByOrderCode(TOKEN)).thenReturn(Optional.of(existing));
        assertThat(service.placeOrder(1L, request, null)).isEqualTo(TOKEN);
        assertThat(cart.getItems()).hasSize(1);
        verifyNoInteractions(carts, stockMutations, addresses, audit);
        request.setPaymentMethod(Order.PaymentMethod.VNPAY);
        assertError(ErrorCode.CHECKOUT_CHANGED, null);
    }

    @Test void rejectsTokenOwnedBySomeoneElse() {
        when(orders.findByOrderCode(TOKEN)).thenReturn(Optional.of(Order.builder()
                .user(User.builder().id(2L).build()).build()));
        assertError(ErrorCode.ORDER_NOT_FOUND, null);
    }

    @Test void rejectsMissingSessionAndInvalidRequest() {
        assertError(ErrorCode.CHECKOUT_CHANGED, null);
        request.setCheckoutToken("invalid");
        assertError(ErrorCode.INVALID_INPUT_DATA, null);
        assertThatThrownBy(() -> service.placeOrder(null, request, null))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test void stockEngineFailureDoesNotClearCart() {
        CheckoutConfirmation reviewed = confirmation();
        doThrow(new AppException(ErrorCode.INSUFFICIENT_STOCK)).when(stockMutations).deductOrder(100L);
        assertError(ErrorCode.INSUFFICIENT_STOCK, reviewed);
        assertThat(cart.getItems()).hasSize(1);
        verifyNoInteractions(audit);
    }

    private void assertError(ErrorCode code, CheckoutConfirmation confirmation) {
        assertThatThrownBy(() -> service.placeOrder(1L, request, confirmation))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(code);
    }
}
