package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.response.VNPayIpnResponse;
import com.ddagtech.aureliabooks.service.VNPayService;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Server-to-server webhook controller receiving asynchronous VNPay IPN notifications (UC11.1).
 * Exempted from session authentication and CSRF filters in SecurityConfig, protected by
 * constant-time HMAC-SHA512 signature verification in {@link VNPayService}.
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class VNPayIpnWebhookController {

    private final VNPayService vnPayService;

    @GetMapping("/vnpay-ipn")
    public ResponseEntity<VNPayIpnResponse> handleIpnGet(
            @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, VNPayUtil.resolveClientIp(request));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/vnpay-ipn")
    public ResponseEntity<VNPayIpnResponse> handleIpnPost(
            @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, VNPayUtil.resolveClientIp(request));
        return ResponseEntity.ok(response);
    }
}
