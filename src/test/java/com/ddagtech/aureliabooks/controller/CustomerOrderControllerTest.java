package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.*;
import com.ddagtech.aureliabooks.service.CustomerOrderService;
import com.ddagtech.aureliabooks.service.CheckoutPaymentGateway;
import com.ddagtech.aureliabooks.service.impl.AccountPresentationServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.domain.PageImpl;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CustomerOrderController.class)
@Import({SecurityConfig.class, CustomAccessDeniedHandler.class, AccountPresentationServiceImpl.class})
class CustomerOrderControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CustomerOrderService orders;
    @MockitoBean CustomerIdentity identity;
    @MockitoBean UserRepository users;
    @MockitoBean CheckoutPaymentGateway gateway;

    @BeforeEach void setup() { when(identity.resolve(any())).thenReturn(7L); }

    @Test void rendersHistory() throws Exception {
        when(orders.getMyOrders(7L, 0, null)).thenReturn(new PageImpl<>(List.of(new OrderHistoryView("code",
                LocalDateTime.now(), BigDecimal.TEN, Order.OrderStatus.CONFIRMED, Order.PaymentStatus.UNPAID))));
        mvc.perform(get("/orders/my-orders").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/orders/my-orders/code")));
    }

    @Test void statusTabIsActiveAndPaginationRetainsFilter() throws Exception {
        var row = new OrderHistoryView("shipping-code", LocalDateTime.now(), BigDecimal.TEN,
                Order.OrderStatus.SHIPPING, Order.PaymentStatus.UNPAID);
        when(orders.getMyOrders(7L, 0, Order.OrderStatus.SHIPPING)).thenReturn(new PageImpl<>(List.of(row),
                org.springframework.data.domain.PageRequest.of(0, 10), 11));
        mvc.perform(get("/orders/my-orders").param("status", "SHIPPING").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*<a(?=[^>]*href=\"/orders/my-orders\\?status=SHIPPING\")(?=[^>]*class=\"is-active\")(?=[^>]*aria-current=\"page\")[^>]*>Đang giao hàng</a>.*")))
                .andExpect(content().string(containsString("/orders/my-orders?page=1&amp;status=SHIPPING")));
        verify(orders).getMyOrders(7L, 0, Order.OrderStatus.SHIPPING);
    }

    @Test void emptyStatusHasSpecificMessage() throws Exception {
        when(orders.getMyOrders(7L, 0, Order.OrderStatus.CANCELLED)).thenReturn(new PageImpl<>(List.of()));
        mvc.perform(get("/orders/my-orders").param("status", "CANCELLED").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Chưa có đơn hàng ở trạng thái này.")));
    }

    @Test void detailRendersTimelineSnapshotsAndCsrf() throws Exception {
        var confirmation = new OrderConfirmationView("code", LocalDateTime.now(), "Người nhận", "0912345678", "Địa chỉ cũ",
                Order.OrderStatus.CONFIRMED, Order.PaymentMethod.COD, Order.PaymentStatus.UNPAID,
                List.of(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN);
        when(orders.getMyOrderDetail(7L, "code")).thenReturn(new OrderDetailView(confirmation, "TRACK123", null,
                List.of(new OrderTimelineStep("Đã đặt hàng", LocalDateTime.now(), false)), true, null));
        mvc.perform(get("/orders/my-orders/code").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Địa chỉ cũ")))
                .andExpect(content().string(containsString("TRACK123")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test void foreignOrderReturns404() throws Exception {
        when(orders.getMyOrderDetail(7L, "foreign")).thenThrow(new AppException(ErrorCode.ORDER_NOT_FOUND));
        mvc.perform(get("/orders/my-orders/foreign").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test void pendingVnpayOrderOffersPaymentOnExistingOrderWithCsrf() throws Exception {
        stubPaymentDetail(Order.OrderStatus.PENDING_PAYMENT, Order.PaymentMethod.VNPAY, Order.PaymentStatus.UNPAID);
        when(gateway.isAvailable()).thenReturn(true);
        mvc.perform(get("/orders/my-orders/code").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/orders/code/pay\"")))
                .andExpect(content().string(containsString("Tiếp tục thanh toán VNPay")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        verify(gateway, never()).startPayment(any(), any());
    }

    @Test void unavailableGatewayExplainsWhyPaymentCannotContinue() throws Exception {
        stubPaymentDetail(Order.OrderStatus.PENDING_PAYMENT, Order.PaymentMethod.VNPAY, Order.PaymentStatus.UNPAID);
        mvc.perform(get("/orders/my-orders/code").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cổng thanh toán tạm thời chưa khả dụng")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("action=\"/orders/code/pay\""))));
    }

    @Test void paidCancelledAndCodOrdersDoNotOfferOnlinePayment() throws Exception {
        when(gateway.isAvailable()).thenReturn(true);
        for (var status : List.of(Order.OrderStatus.CANCELLED, Order.OrderStatus.CONFIRMED, Order.OrderStatus.RETURNED)) {
            stubPaymentDetail(status, Order.PaymentMethod.VNPAY, Order.PaymentStatus.UNPAID);
            assertNoPaymentForm();
        }
        stubPaymentDetail(Order.OrderStatus.PENDING_PAYMENT, Order.PaymentMethod.VNPAY, Order.PaymentStatus.PAID);
        assertNoPaymentForm();
        stubPaymentDetail(Order.OrderStatus.PENDING_CONFIRMATION, Order.PaymentMethod.COD, Order.PaymentStatus.UNPAID);
        assertNoPaymentForm();
    }

    private void assertNoPaymentForm() throws Exception {
        mvc.perform(get("/orders/my-orders/code").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("action=\"/orders/code/pay\""))));
    }

    private void stubPaymentDetail(Order.OrderStatus status, Order.PaymentMethod method, Order.PaymentStatus payment) {
        var confirmation = new OrderConfirmationView("code", LocalDateTime.now(), "Người nhận", "0912345678", "Địa chỉ",
                status, method, payment, List.of(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN);
        when(orders.getMyOrderDetail(7L, "code")).thenReturn(new OrderDetailView(confirmation, null, null, List.of(), false, null));
    }

    @Test void cancellationRequiresCsrf() throws Exception {
        mvc.perform(post("/orders/my-orders/code/cancel").with(user("customer").roles("CUSTOMER"))
                .param("reason", "Hủy")).andExpect(status().isForbidden());
        verify(orders, never()).cancelMyOrder(any(), any(), any());
    }

    @Test void cancellationUsesAuthenticatedOwner() throws Exception {
        mvc.perform(post("/orders/my-orders/code/cancel").with(user("customer").roles("CUSTOMER")).with(csrf())
                .param("userId", "999").param("reason", "Thay đổi nhu cầu"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/orders/my-orders/code"));
        verify(orders).cancelMyOrder(eq(7L), eq("code"), any());
    }

    @Test void blankReasonDoesNotCancel() throws Exception {
        mvc.perform(post("/orders/my-orders/code/cancel").with(user("customer").roles("CUSTOMER")).with(csrf())
                .param("reason", " ")).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"));
        verify(orders, never()).cancelMyOrder(any(), any(), any());
    }
}
