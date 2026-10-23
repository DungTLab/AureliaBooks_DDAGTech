package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.response.VNPayPaymentResultView;
import com.ddagtech.aureliabooks.service.VNPayService;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Storefront controller orchestrating VNPay payment redirection and the read-only
 * SCR-ST-13 (Payment & Order Result) browser return screen (UC11.1).
 *
 * Per C08, SCR-ST-12 (VNPay Payment Waiting & QR) is hosted externally by the VNPay gateway;
 * AureliaBook redirects directly to VNPay and renders SCR-ST-13 upon browser return.
 */
@Controller
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final VNPayService vnPayService;

    /**
     * Generates a signed VNPay Sandbox payment URL for a PENDING_PAYMENT order and redirects the customer.
     */
    @GetMapping("/vnpay/create/{orderId}")
    public String redirectToVNPayGet(@PathVariable Long orderId, HttpServletRequest request) {
        String paymentUrl = vnPayService.createPaymentUrl(orderId, VNPayUtil.resolveClientIp(request));
        return "redirect:" + paymentUrl;
    }

    /**
     * Supports POST-initiated redirect to the VNPay Sandbox gateway after order creation (UC11 -> UC11.1).
     */
    @PostMapping("/vnpay/create/{orderId}")
    public String redirectToVNPayPost(@PathVariable Long orderId, HttpServletRequest request) {
        String paymentUrl = vnPayService.createPaymentUrl(orderId, VNPayUtil.resolveClientIp(request));
        return "redirect:" + paymentUrl;
    }

    /**
     * Renders SCR-ST-13 (Payment & Order Result) when the customer's browser returns from VNPay.
     * Strictly presentation-only; authoritative state transitions occur exclusively via the IPN webhook.
     */
    @GetMapping("/vnpay-return")
    public String handleVNPayBrowserReturn(@RequestParam Map<String, String> params, Model model) {
        VNPayPaymentResultView resultView = vnPayService.evaluateBrowserReturn(params);
        model.addAttribute("paymentResult", resultView);
        return "payment/result";
    }
}
