package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.response.VNPayIpnResponse;
import com.ddagtech.aureliabooks.dto.response.VNPayPaymentResultView;
import com.ddagtech.aureliabooks.service.VNPayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * WebMvcTest suite for {@link PaymentController} and {@link VNPayIpnWebhookController} (UC11.1 / C08).
 * Verifies:
 * - Server-to-server VNPay IPN webhook (/api/v1/payments/vnpay-ipn) is accessible without session/CSRF.
 * - Authenticated customer can initiate redirect to VNPay Sandbox and view SCR-ST-13 (payment/result).
 */
@WebMvcTest(controllers = {PaymentController.class, VNPayIpnWebhookController.class})
@Import(SecurityConfig.class)
class VNPayPaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VNPayService vnPayService;

    @Test
    @DisplayName("POST /api/v1/payments/vnpay-ipn should be accessible without CSRF or session login and return JSON RspCode")
    void handleIpnPost_WithoutCsrfOrAuth_ReturnsJsonRspCode() throws Exception {
        when(vnPayService.processIpnWebhook(anyMap(), any()))
                .thenReturn(VNPayIpnResponse.of("00", "Confirm Success"));

        mockMvc.perform(post("/api/v1/payments/vnpay-ipn")
                        .param("vnp_TxnRef", "ORD-20261016-001")
                        .param("vnp_ResponseCode", "00")
                        .param("vnp_SecureHash", "signedhash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("00"))
                .andExpect(jsonPath("$.Message").value("Confirm Success"));
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("GET /payment/vnpay/create/{orderId} should redirect authenticated customer to VNPay Sandbox URL")
    void redirectToVNPayGet_AuthenticatedCustomer_RedirectsToGateway() throws Exception {
        String targetUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=ORD-001";
        when(vnPayService.createPaymentUrl(eq(100L), any())).thenReturn(targetUrl);

        mockMvc.perform(get("/payment/vnpay/create/100"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(targetUrl));
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("GET /payment/vnpay-return should render SCR-ST-13 (payment/result) with read-only paymentResult model")
    void handleVNPayBrowserReturn_AuthenticatedCustomer_RendersResultView() throws Exception {
        VNPayPaymentResultView viewModel = new VNPayPaymentResultView(
                true,
                true,
                "ORD-20261016-001",
                new BigDecimal("250000.00"),
                "00",
                "00",
                "NCB",
                "20261016103000",
                "PENDING_CONFIRMATION",
                "PAID",
                "Giao dịch thanh toán VNPay thành công."
        );
        when(vnPayService.evaluateBrowserReturn(anyMap())).thenReturn(viewModel);

        mockMvc.perform(get("/payment/vnpay-return")
                        .param("vnp_TxnRef", "ORD-20261016-001")
                        .param("vnp_ResponseCode", "00"))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/result"))
                .andExpect(model().attribute("paymentResult", viewModel));
    }
}
