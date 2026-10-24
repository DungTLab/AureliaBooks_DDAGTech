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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * WebMvcTest suite for {@link PaymentController} and {@link VNPayIpnWebhookController} (UC11.1 / C08 / F04 / F08 / F12).
 * Verifies:
 * - Server-to-server VNPay IPN webhook (/api/v1/payments/vnpay-ipn) is accessible without session/CSRF.
 * - /payment/** requires ROLE_CUSTOMER authentication; guests are redirected to /login and staff/admin receive 403.
 * - POST /payment/vnpay/create/{orderId} enforces CSRF protection.
 * - SCR-ST-13 (payment/result) renders distinct headings for confirmed PAID, 00/00 before IPN,
 *   incomplete/unknown (00/01), verified terminal failure (24/02), late success on CANCELLED, and invalid signature (F12).
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
    @DisplayName("F04: GET /payment/vnpay/create/{orderId} should redirect authenticated customer to VNPay Sandbox URL")
    void redirectToVNPayGet_AuthenticatedCustomer_RedirectsToGateway() throws Exception {
        String targetUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=ORD-001";
        when(vnPayService.createPaymentUrl(eq(100L), any())).thenReturn(targetUrl);

        mockMvc.perform(get("/payment/vnpay/create/100"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(targetUrl));
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("F04: POST /payment/vnpay/create/{orderId} with valid CSRF token should redirect authenticated customer to VNPay Sandbox URL")
    void redirectToVNPayPost_WithCsrf_RedirectsToGateway() throws Exception {
        String targetUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=ORD-002";
        when(vnPayService.createPaymentUrl(eq(102L), any())).thenReturn(targetUrl);

        mockMvc.perform(post("/payment/vnpay/create/102").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(targetUrl));
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("F04: POST /payment/vnpay/create/{orderId} without CSRF token should be rejected with 403 Forbidden")
    void redirectToVNPayPost_WithoutCsrf_Returns403Forbidden() throws Exception {
        mockMvc.perform(post("/payment/vnpay/create/102"))
                .andExpect(status().isForbidden());

        verify(vnPayService, never()).createPaymentUrl(any(), any());
    }

    @Test
    @DisplayName("F04: GET /payment/vnpay/create/{orderId} by unauthenticated guest should redirect to /login")
    void redirectToVNPayGet_UnauthenticatedGuest_RedirectsToLogin() throws Exception {
        mockMvc.perform(get("/payment/vnpay/create/100"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(vnPayService, never()).createPaymentUrl(any(), any());
    }

    @Test
    @WithMockUser(username = "staff@aureliabook.vn", roles = {"SALE_STAFF"})
    @DisplayName("F04: GET /payment/vnpay/create/{orderId} by non-customer role (SALE_STAFF) should be rejected with 403 Forbidden")
    void redirectToVNPayGet_SaleStaffRole_Returns403Forbidden() throws Exception {
        mockMvc.perform(get("/payment/vnpay/create/100"))
                .andExpect(status().isForbidden());

        verify(vnPayService, never()).createPaymentUrl(any(), any());
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("F08/F12: GET /payment/vnpay-return for confirmed PAID order should render SCR-ST-13 with success heading")
    void handleVNPayBrowserReturn_ConfirmedPaid_RendersSuccessHeading() throws Exception {
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
                "Giao dịch thanh toán VNPay đã được xác minh thành công. Đơn hàng của bạn đang chờ xác nhận."
        );
        when(vnPayService.evaluateBrowserReturn(anyMap())).thenReturn(viewModel);

        mockMvc.perform(get("/payment/vnpay-return")
                        .param("vnp_TxnRef", "ORD-20261016-001")
                        .param("vnp_ResponseCode", "00"))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/result"))
                .andExpect(model().attribute("paymentResult", viewModel))
                .andExpect(content().string(containsString("Thanh Toán Thành Công")))
                .andExpect(content().string(not(containsString("Thanh Toán Không Thành Công"))));
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("F08/F12: GET /payment/vnpay-return for 00/00 before IPN settles should render verification heading instead of success or failure")
    void handleVNPayBrowserReturn_SuccessReturnBeforeIpn_RendersVerificationHeading() throws Exception {
        VNPayPaymentResultView viewModel = new VNPayPaymentResultView(
                true,
                false,
                "ORD-20261016-002",
                new BigDecimal("250000.00"),
                "00",
                "00",
                "NCB",
                "20261016103000",
                "PENDING_PAYMENT",
                "UNPAID",
                "Đã nhận phản hồi từ VNPay. Hệ thống đang đối soát và xác minh kết quả thanh toán."
        );
        when(vnPayService.evaluateBrowserReturn(anyMap())).thenReturn(viewModel);

        mockMvc.perform(get("/payment/vnpay-return")
                        .param("vnp_TxnRef", "ORD-20261016-002")
                        .param("vnp_ResponseCode", "00")
                        .param("vnp_TransactionStatus", "00"))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/result"))
                .andExpect(content().string(containsString("Đang Xác Minh Thanh Toán")))
                .andExpect(content().string(not(containsString("Thanh Toán Thành Công"))))
                .andExpect(content().string(not(containsString("Thanh Toán Không Thành Công"))));
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("F12: GET /payment/vnpay-return for incomplete/unknown status (00/01) must render verification heading, never 'Thanh Toán Không Thành Công'")
    void handleVNPayBrowserReturn_IncompleteStatus0001_RendersVerificationHeadingNotFailure() throws Exception {
        VNPayPaymentResultView viewModel = new VNPayPaymentResultView(
                true,
                false,
                "ORD-20261016-003",
                new BigDecimal("250000.00"),
                "00",
                "01",
                "NCB",
                "20261016103000",
                "PENDING_PAYMENT",
                "UNPAID",
                "Trạng thái giao dịch VNPay chưa hoàn tất hoặc chưa xác định. Hệ thống đang chờ đối soát."
        );
        when(vnPayService.evaluateBrowserReturn(anyMap())).thenReturn(viewModel);

        mockMvc.perform(get("/payment/vnpay-return")
                        .param("vnp_TxnRef", "ORD-20261016-003")
                        .param("vnp_ResponseCode", "00")
                        .param("vnp_TransactionStatus", "01"))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/result"))
                .andExpect(content().string(containsString("Đang Xác Minh Thanh Toán")))
                .andExpect(content().string(containsString("Trạng thái giao dịch VNPay chưa hoàn tất hoặc chưa xác định. Hệ thống đang chờ đối soát.")))
                .andExpect(content().string(not(containsString("Thanh Toán Không Thành Công"))))
                .andExpect(content().string(not(containsString("Thanh Toán Thành Công"))));
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("F12: GET /payment/vnpay-return for verified terminal failure (24/02) should render 'Thanh Toán Không Thành Công'")
    void handleVNPayBrowserReturn_VerifiedTerminalFailure2402_RendersFailureHeading() throws Exception {
        VNPayPaymentResultView viewModel = new VNPayPaymentResultView(
                true,
                false,
                "ORD-20261016-004",
                new BigDecimal("250000.00"),
                "24",
                "02",
                "NCB",
                "20261016103000",
                "CANCELLED",
                "UNPAID",
                "Giao dịch thanh toán VNPay không thành công hoặc đã bị hủy."
        );
        when(vnPayService.evaluateBrowserReturn(anyMap())).thenReturn(viewModel);

        mockMvc.perform(get("/payment/vnpay-return")
                        .param("vnp_TxnRef", "ORD-20261016-004")
                        .param("vnp_ResponseCode", "24")
                        .param("vnp_TransactionStatus", "02"))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/result"))
                .andExpect(content().string(containsString("Thanh Toán Không Thành Công")))
                .andExpect(content().string(not(containsString("Thanh Toán Thành Công"))));
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("F08/F12: GET /payment/vnpay-return for late success on CANCELLED order should render reconciliation heading instead of success or failure")
    void handleVNPayBrowserReturn_LateSuccessAfterCancelled_RendersReconciliationHeading() throws Exception {
        VNPayPaymentResultView viewModel = new VNPayPaymentResultView(
                true,
                false,
                "ORD-20261016-060",
                new BigDecimal("400000.00"),
                "00",
                "00",
                "NCB",
                "20261016103000",
                "CANCELLED",
                "UNPAID",
                "Đơn hàng đã bị hủy trước đó. Giao dịch thanh toán đến muộn đã được ghi nhận để đối soát và xử lý hoàn tiền."
        );
        when(vnPayService.evaluateBrowserReturn(anyMap())).thenReturn(viewModel);

        mockMvc.perform(get("/payment/vnpay-return")
                        .param("vnp_TxnRef", "ORD-20261016-060")
                        .param("vnp_ResponseCode", "00"))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/result"))
                .andExpect(model().attribute("paymentResult", viewModel))
                .andExpect(content().string(containsString("Giao Dịch Đến Muộn — Đang Đối Soát")))
                .andExpect(content().string(not(containsString("Thanh Toán Thành Công"))))
                .andExpect(content().string(not(containsString("Thanh Toán Không Thành Công"))));
    }

    @Test
    @WithMockUser(username = "customer@aureliabook.vn", roles = {"CUSTOMER"})
    @DisplayName("F12: GET /payment/vnpay-return with invalid signature should render signature error heading")
    void handleVNPayBrowserReturn_InvalidSignature_RendersInvalidSignatureHeading() throws Exception {
        VNPayPaymentResultView viewModel = new VNPayPaymentResultView(
                false,
                false,
                "ORD-20261016-005",
                new BigDecimal("250000.00"),
                "00",
                "00",
                "NCB",
                "20261016103000",
                "UNKNOWN",
                "UNKNOWN",
                "Chữ ký xác thực phản hồi từ cổng thanh toán không hợp lệ."
        );
        when(vnPayService.evaluateBrowserReturn(anyMap())).thenReturn(viewModel);

        mockMvc.perform(get("/payment/vnpay-return")
                        .param("vnp_TxnRef", "ORD-20261016-005")
                        .param("vnp_SecureHash", "forged"))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/result"))
                .andExpect(content().string(containsString("Không Thể Xác Thực Giao Dịch")))
                .andExpect(content().string(not(containsString("Thanh Toán Thành Công"))))
                .andExpect(content().string(not(containsString("Thanh Toán Không Thành Công"))));
    }
}
