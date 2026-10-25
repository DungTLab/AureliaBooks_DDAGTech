package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.service.CheckoutPaymentGateway;
import com.ddagtech.aureliabooks.service.VNPayService;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Adapter bridging UC11 Checkout with UC11.1 VNPay Sandbox payment gateway.
 * Enforces V2.4 invariants:
 * - Starting payment does not deduct stock or mark payment successful.
 * - Redirect URL is cryptographically signed with HMAC-SHA512 and targets VNPay Sandbox.
 * - Fails closed when merchant credentials are not configured.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VNPayCheckoutPaymentAdapter implements CheckoutPaymentGateway {

    private final VNPayConfig vnPayConfig;
    private final VNPayService vnPayService;
    private final OrderRepository orderRepository;

    @Override
    public boolean isAvailable() {
        return vnPayConfig != null
                && vnPayConfig.getHashSecret() != null
                && !vnPayConfig.getHashSecret().isBlank()
                && vnPayConfig.getTmnCode() != null
                && !vnPayConfig.getTmnCode().isBlank();
    }

    @Override
    public String startPayment(Long authenticatedUserId, String orderCode) {
        if (!isAvailable()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "VNPay gateway is currently unavailable.");
        }

        Order order = orderRepository.findByOrderCodeAndUser_Id(orderCode, authenticatedUserId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND, "Order not found for checkout payment"));

        String clientIp = "127.0.0.1";
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            clientIp = VNPayUtil.resolveClientIp(request);
        }

        return vnPayService.createPaymentUrl(authenticatedUserId, order.getId(), clientIp);
    }
}
