package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.response.AddressView;
import com.ddagtech.aureliabooks.dto.response.CartItemView;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.dto.response.OrderConfirmationView;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.ShippingAddress.EconomicRegion;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.security.CustomAccessDeniedHandler;
import com.ddagtech.aureliabooks.service.AddressService;
import com.ddagtech.aureliabooks.service.CheckoutPaymentGateway;
import com.ddagtech.aureliabooks.service.OrderPlacementService;
import com.ddagtech.aureliabooks.service.impl.AccountPresentationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Render real Thymeleaf with mocked services to test forms, CSRF, ownership, session reviews, and VNPay handoff. */
@WebMvcTest(CheckoutController.class)
@Import({SecurityConfig.class, CustomAccessDeniedHandler.class, AccountPresentationServiceImpl.class,
        CheckoutSessionStore.class})
class CheckoutControllerTest {
    private static final String CODE = "0123456789abcdef0123456789abcdef";
    @Autowired MockMvc mvc;
    @Autowired CheckoutSessionStore reviews;
    @MockitoBean OrderPlacementService orders;
    @MockitoBean AddressService addresses;
    @MockitoBean CustomerIdentity identity;
    @MockitoBean UserRepository users;
    @MockitoBean CheckoutPaymentGateway gateway;
    private CheckoutView checkout;
    private OrderConfirmationView order;

    @BeforeEach void setup() {
        when(identity.resolve(any())).thenReturn(7L);
        List<AddressView> destinations = List.of(
                address(10L, EconomicRegion.NORTHERN, "Hà Nội"),
                address(11L, EconomicRegion.CENTRAL_HIGHLANDS, "Đà Nẵng"),
                address(12L, EconomicRegion.SOUTHERN_MEKONG, "TP. Hồ Chí Minh"));
        checkout = new CheckoutView(List.of(new CartItemView(1L, 20L, "Đi tìm lẽ sống", null,
                money("100000"), 2, money("200000"), 5, true, null)), destinations, 10L,
                money("200000"), BigDecimal.ZERO, money("50000"), BigDecimal.ZERO,
                money("50000"), money("250000"));
        order = new OrderConfirmationView(CODE, LocalDateTime.of(2026, 10, 18, 10, 0),
                "Nguyễn Minh Anh", "0912345678", "12 Đội Cấn, Đội Cấn, Ba Đình, Hà Nội",
                Order.OrderStatus.PENDING_CONFIRMATION, Order.PaymentMethod.COD, Order.PaymentStatus.UNPAID,
                List.of(new OrderConfirmationView.Item(20L, "Đi tìm lẽ sống", 2, money("100000"), money("200000"))),
                money("200000"), BigDecimal.ZERO, money("50000"), money("250000"));
        when(orders.getCheckoutView(eq(7L), any())).thenReturn(checkout);
        when(orders.getOrderConfirmation(7L, CODE)).thenReturn(order);
    }

    @Test void rendersAddressesRegionalFeesAndUnavailableOnlinePayment() throws Exception {
        mvc.perform(get("/checkout").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(view().name("checkout/view"))
                .andExpect(content().string(containsString("50.000 đ")))
                .andExpect(content().string(containsString("40.000 đ")))
                .andExpect(content().string(containsString("30.000 đ")))
                .andExpect(content().string(containsString("250.000")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("Thanh toán trực tuyến tạm thời chưa khả dụng")));
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    @Test void checkoutDisplaysDeliveryPhoneWithoutAccountVerificationGate() throws Exception {
        mvc.perform(get("/checkout").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(checkout.addressViews().getFirst().phone())))
                .andExpect(content().string(not(containsString("Xác minh số điện thoại để đặt hàng"))))
                .andExpect(model().attributeDoesNotExist("phoneReady"));
    }

    @Test void missingAddressShowsInlineFormAndDoesNotInventZeroFee() throws Exception {
        CheckoutView emptyAddresses = new CheckoutView(checkout.items(), List.of(), null,
                checkout.merchandiseSubtotal(), BigDecimal.ZERO, null, BigDecimal.ZERO, null, null);
        when(orders.getCheckoutView(eq(7L), any())).thenReturn(emptyAddresses);
        mvc.perform(get("/checkout").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Bạn chưa có địa chỉ giao hàng")))
                .andExpect(content().string(containsString("/checkout/addresses")))
                .andExpect(content().string(containsString("Chọn địa chỉ")));
    }

    /** Submit forged userId and totals to verify that the controller uses authenticated ownership and server-side data. */
    @Test void preparesServerReviewAndReusesItOnRepeatedSubmission() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var first = mvc.perform(post("/checkout/review").session(session)
                        .with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("shippingAddressId", "10").param("paymentMethod", "COD")
                        .param("userId", "999").param("finalTotalAmount", "1"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String destination = first.getResponse().getRedirectedUrl();
        assertThat(destination).matches("/checkout/review/[a-f0-9]{32}");
        mvc.perform(post("/checkout/review").session(session)
                        .with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("shippingAddressId", "10").param("paymentMethod", "COD"))
                .andExpect(redirectedUrl(destination));
        mvc.perform(get(destination).session(session).with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(view().name("checkout/review"))
                .andExpect(content().string(containsString("250.000")))
                .andExpect(content().string(containsString("name=\"checkoutToken\"")));
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    @Test void finalSubmitUsesOnlySessionReviewAndAuthenticatedOwner() throws Exception {
        MockHttpSession session = new MockHttpSession();
        CheckoutConfirmation review = reviews.save(session, 7L, checkout, Order.PaymentMethod.COD, null);
        when(orders.placeOrder(eq(7L), any(), eq(review))).thenReturn(review.checkoutToken());
        mvc.perform(post("/checkout/place").session(session).with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("checkoutToken", review.checkoutToken()).param("shippingAddressId", "10")
                        .param("paymentMethod", "COD").param("userId", "999").param("finalTotalAmount", "1"))
                .andExpect(redirectedUrl("/orders/" + review.checkoutToken()));
        assertThat(reviews.find(session, review.checkoutToken(), 7L)).isNull();
        verify(orders).placeOrder(eq(7L), argThat(form -> form.getShippingAddressId().equals(10L)), eq(review));
    }

    @Test void replayWithoutSessionReviewCanReturnCommittedOrder() throws Exception {
        when(orders.placeOrder(eq(7L), any(), isNull())).thenReturn(CODE);
        mvc.perform(post("/checkout/place").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("checkoutToken", CODE).param("shippingAddressId", "10").param("paymentMethod", "COD"))
                .andExpect(redirectedUrl("/orders/" + CODE));
    }

    @Test void forgedTokenCannotSupplyConfirmationFromBrowser() throws Exception {
        when(orders.placeOrder(eq(7L), any(), isNull())).thenThrow(new AppException(ErrorCode.CHECKOUT_CHANGED));
        mvc.perform(post("/checkout/place").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("checkoutToken", CODE).param("shippingAddressId", "10").param("paymentMethod", "COD")
                        .param("confirmation.userId", "7"))
                .andExpect(redirectedUrl("/checkout")).andExpect(flash().attributeExists("errorMessage"));
    }

    @Test void changedReviewRequiresAnotherExplicitConfirmation() throws Exception {
        MockHttpSession session = new MockHttpSession();
        CheckoutConfirmation review = reviews.save(session, 7L, checkout, Order.PaymentMethod.COD, null);
        when(orders.placeOrder(eq(7L), any(), eq(review))).thenThrow(new AppException(ErrorCode.CHECKOUT_CHANGED));
        mvc.perform(post("/checkout/place").session(session).with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("checkoutToken", review.checkoutToken()).param("shippingAddressId", "10").param("paymentMethod", "COD"))
                .andExpect(redirectedUrl("/checkout")).andExpect(flash().attribute("errorMessage", ErrorCode.CHECKOUT_CHANGED.getMessage()));
        assertThat(reviews.find(session, review.checkoutToken(), 7L)).isNull();
    }

    @Test void blocksUnconfiguredOnlinePaymentBeforeAnyOrderIsCreated() throws Exception {
        mvc.perform(post("/checkout/review").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("shippingAddressId", "10").param("paymentMethod", "VNPAY"))
                .andExpect(redirectedUrl("/checkout"));
        mvc.perform(post("/checkout/place").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("checkoutToken", CODE).param("shippingAddressId", "10").param("paymentMethod", "VNPAY"))
                .andExpect(redirectedUrl("/checkout"));
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    /** The mock adapter verifies handoff only; it does not simulate callbacks or establish successful VNPay payment. */
    @Test void configuredGatewayReceivesCommittedOrderOnly() throws Exception {
        when(gateway.isAvailable()).thenReturn(true);
        MockHttpSession session = new MockHttpSession();
        CheckoutConfirmation review = reviews.save(session, 7L, checkout, Order.PaymentMethod.VNPAY, null);
        when(orders.placeOrder(eq(7L), any(), eq(review))).thenReturn(review.checkoutToken());
        when(gateway.startPayment(7L, review.checkoutToken())).thenReturn("https://sandbox.example.test/pay");
        mvc.perform(post("/checkout/place").session(session).with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("checkoutToken", review.checkoutToken()).param("shippingAddressId", "10").param("paymentMethod", "VNPAY"))
                .andExpect(redirectedUrl("https://sandbox.example.test/pay"));
        var sequence = inOrder(orders, gateway);
        sequence.verify(orders).placeOrder(eq(7L), any(), eq(review));
        sequence.verify(gateway).startPayment(7L, review.checkoutToken());
    }

    /** Gateway failure after order creation must preserve the committed order and offer a retry. */
    @Test void gatewayFailureKeepsCommittedOrderAndShowsRetryMessage() throws Exception {
        when(gateway.isAvailable()).thenReturn(true);
        when(orders.placeOrder(eq(7L), any(), isNull())).thenReturn(CODE);
        when(gateway.startPayment(7L, CODE)).thenThrow(new IllegalStateException("QA gateway failure"));
        mvc.perform(post("/checkout/place").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("checkoutToken", CODE).param("shippingAddressId", "10").param("paymentMethod", "VNPAY"))
                .andExpect(redirectedUrl("/orders/" + CODE))
                .andExpect(flash().attribute("errorMessage", containsString("Đơn hàng đã được tạo")));
    }

    @Test void confirmationUsesStoredStatusAndSupportsPrinting() throws Exception {
        mvc.perform(get("/orders/" + CODE).param("paymentStatus", "PAID").param("vnp_ResponseCode", "00")
                        .with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(view().name("checkout/order-confirm"))
                .andExpect(content().string(containsString("Chưa thanh toán")))
                .andExpect(content().string(not(containsString(">Đã thanh toán<"))))
                .andExpect(content().string(containsString("data-print-order")))
                .andExpect(content().string(containsString("12 Đội Cấn")));
        verify(orders).getOrderConfirmation(7L, CODE);
    }

    @Test void foreignOrMissingOrderReturns404() throws Exception {
        when(orders.getOrderConfirmation(7L, CODE)).thenThrow(new AppException(ErrorCode.ORDER_NOT_FOUND));
        mvc.perform(get("/orders/" + CODE).with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isNotFound()).andExpect(view().name("error/404"));
    }

    @Test void missingOrForeignReviewRedirectsSafely() throws Exception {
        mvc.perform(get("/checkout/review/" + CODE).with(user("customer").roles("CUSTOMER")))
                .andExpect(redirectedUrl("/checkout")).andExpect(flash().attributeExists("errorMessage"));
    }

    @Test void invalidParametersAndMissingFieldsDoNotCreateOrders() throws Exception {
        mvc.perform(get("/checkout").param("shippingAddressId", "invalid").with(user("customer").roles("CUSTOMER")))
                .andExpect(redirectedUrl("/checkout"));
        mvc.perform(post("/checkout/review").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("shippingAddressId", "10").param("paymentMethod", "INVALID"))
                .andExpect(redirectedUrl("/checkout"));
        mvc.perform(post("/checkout/place").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("shippingAddressId", "10").param("paymentMethod", "COD"))
                .andExpect(redirectedUrl("/checkout"));
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    @Test void inlineAddressValidationPreservesTypedValues() throws Exception {
        mvc.perform(post("/checkout/addresses").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("recipientName", "Khách mới").param("phone", "invalid"))
                .andExpect(status().isOk()).andExpect(view().name("checkout/view"))
                .andExpect(model().attributeHasFieldErrors("addressForm", "phone"))
                .andExpect(result -> assertThat(org.springframework.web.util.HtmlUtils.htmlUnescape(
                        result.getResponse().getContentAsString())).contains("Khách mới"));
        verifyNoInteractions(addresses);
    }

    @Test void inlineAddressIsSavedForAuthenticatedOwner() throws Exception {
        when(addresses.create(eq(7L), any())).thenReturn(15L);
        mvc.perform(post("/checkout/addresses").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("recipientName", "Khách mới").param("phone", "0912345678")
                        .param("economicRegion", "NORTHERN").param("province", "Hà Nội")
                        .param("district", "Ba Đình").param("ward", "Đội Cấn").param("detailedAddress", "12 Đội Cấn")
                        .param("userId", "999"))
                .andExpect(redirectedUrl("/checkout?shippingAddressId=15"));
        verify(addresses).create(eq(7L), any());
    }

    @Test void csrfAndRoleProtectionApplyToAllCheckoutMutations() throws Exception {
        mvc.perform(post("/checkout/place").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/checkout/review").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/checkout").with(user("staff").roles("SALE_STAFF")))
                .andExpect(redirectedUrl("/error/403"));
        mvc.perform(get("/checkout")).andExpect(status().is3xxRedirection());
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    @Test void readFailureIs503AndDoesNotPretendCartIsEmpty() throws Exception {
        when(orders.getCheckoutView(eq(7L), any())).thenThrow(new IllegalStateException("QA read failure"));
        mvc.perform(get("/checkout").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isServiceUnavailable()).andExpect(view().name("checkout/load-error"));
    }

    /** Export Thymeleaf HTML to target for layout inspection using fixture data only. */
    @Test void capturesRenderedScreensForVisualQa() throws Exception {
        Path directory = Path.of("target/uc11-preview"); Files.createDirectories(directory);
        var session = new MockHttpSession();
        var review = reviews.save(session, 7L, checkout, Order.PaymentMethod.COD, null);
        for (var page : List.of(new String[]{"checkout.html", "/checkout"},
                new String[]{"review.html", "/checkout/review/" + review.checkoutToken()},
                new String[]{"order-confirm.html", "/orders/" + CODE})) {
            String html = mvc.perform(get(page[1]).session(session).with(user("customer").roles("CUSTOMER")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            Files.writeString(directory.resolve(page[0]), html);
        }
    }

    private AddressView address(long id, EconomicRegion region, String province) {
        return new AddressView(id, "Nguyễn Minh Anh", "0912345678", region, province,
                "Ba Đình", "Đội Cấn", "12 Đội Cấn", id == 10);
    }
    private static BigDecimal money(String value) { return new BigDecimal(value); }
}
