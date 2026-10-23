package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.service.VNPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VNPayCheckoutPaymentAdapter: Bridge between UC11 Checkout and UC11.1 VNPay Gateway")
class VNPayCheckoutPaymentAdapterTest {

    @Mock
    private VNPayConfig vnPayConfig;

    @Mock
    private VNPayService vnPayService;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private VNPayCheckoutPaymentAdapter adapter;

    private Order testOrder;

    @BeforeEach
    void setUp() {
        testOrder = new Order();
        testOrder.setId(101L);
        testOrder.setOrderCode("a1b2c3d4e5f60718293a4b5c6d7e8f90");
        testOrder.setOrderStatus(Order.OrderStatus.PENDING_PAYMENT);
        testOrder.setPaymentStatus(Order.PaymentStatus.UNPAID);
    }

    @Test
    @DisplayName("isAvailable returns true when tmnCode and hashSecret are configured")
    void isAvailable_whenConfigured_returnsTrue() {
        when(vnPayConfig.getTmnCode()).thenReturn("DEMOTMN01");
        when(vnPayConfig.getHashSecret()).thenReturn("DEMOSECRET123");

        assertThat(adapter.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("isAvailable returns false when tmnCode or hashSecret is blank")
    void isAvailable_whenSecretBlank_returnsFalse() {
        when(vnPayConfig.getHashSecret()).thenReturn("");

        assertThat(adapter.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("startPayment returns payment URL when order is valid and owned by user")
    void startPayment_success() {
        when(vnPayConfig.getTmnCode()).thenReturn("DEMOTMN01");
        when(vnPayConfig.getHashSecret()).thenReturn("DEMOSECRET123");
        when(orderRepository.findByOrderCodeAndUser_Id(testOrder.getOrderCode(), 1L))
                .thenReturn(Optional.of(testOrder));
        when(vnPayService.createPaymentUrl(eq(101L), any()))
                .thenReturn("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=1000000");

        String url = adapter.startPayment(1L, testOrder.getOrderCode());

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        verify(vnPayService).createPaymentUrl(eq(101L), any());
    }

    @Test
    @DisplayName("startPayment throws AppException if gateway is unavailable")
    void startPayment_unavailable_throwsException() {
        when(vnPayConfig.getHashSecret()).thenReturn("");

        assertThatThrownBy(() -> adapter.startPayment(1L, testOrder.getOrderCode()))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA));
    }

    @Test
    @DisplayName("startPayment throws AppException when order not found or not owned by user")
    void startPayment_orderNotFound_throwsException() {
        when(vnPayConfig.getTmnCode()).thenReturn("DEMOTMN01");
        when(vnPayConfig.getHashSecret()).thenReturn("DEMOSECRET123");
        when(orderRepository.findByOrderCodeAndUser_Id("nonexistent", 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.startPayment(1L, "nonexistent"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
    }
}
