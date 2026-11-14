package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.response.VNPayIpnResponse;
import com.ddagtech.aureliabooks.service.VNPayService;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class VNPayIpnWebhookController {

    private static final VNPayIpnResponse UNKNOWN_ERROR = VNPayIpnResponse.of("99", "Unknown error");

    private final VNPayService vnPayService;

    @GetMapping("/vnpay-ipn")
    public ResponseEntity<VNPayIpnResponse> handleIpnGet(
            @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        return ResponseEntity.ok(processSafely(params, request));
    }

    @PostMapping("/vnpay-ipn")
    public ResponseEntity<VNPayIpnResponse> handleIpnPost(
            @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        return ResponseEntity.ok(processSafely(params, request));
    }

    /**
     * Always yields a VNPay-shaped JSON body: an unexpected failure must not leak into the generic
     * HTML/JSON error handler, which VNPay cannot interpret. The cause is logged server-side only.
     */
    private VNPayIpnResponse processSafely(Map<String, String> params, HttpServletRequest request) {
        try {
            return vnPayService.processIpnWebhook(params, VNPayUtil.resolveClientIp(request));
        } catch (RuntimeException ex) {
            log.error("Unexpected failure while processing VNPay IPN (txnRef={})",
                    params == null ? null : params.get("vnp_TxnRef"), ex);
            return UNKNOWN_ERROR;
        }
    }
}
