package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.VNPayPaymentResultView;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.VNPayService;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
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
public class PaymentController {

    private final VNPayService vnPayService;
    private CustomerIdentity customerIdentity;

    @Autowired
    public PaymentController(VNPayService vnPayService) {
        this.vnPayService = vnPayService;
    }

    @Autowired(required = false)
    public void setCustomerIdentity(CustomerIdentity customerIdentity) {
        this.customerIdentity = customerIdentity;
    }

    /**
     * Generates a signed VNPay Sandbox payment URL for a PENDING_PAYMENT order owned by the authenticated customer.
     */
    @GetMapping("/vnpay/create/{orderId}")
    public String redirectToVNPayGet(@PathVariable Long orderId, HttpServletRequest request) {
        requireCustomerAuthentication();
        String paymentUrl = vnPayService.createPaymentUrl(orderId, VNPayUtil.resolveClientIp(request));
        return "redirect:" + paymentUrl;
    }

    /**
     * Supports POST-initiated redirect to the VNPay Sandbox gateway after order creation (UC11 -> UC11.1).
     */
    @PostMapping("/vnpay/create/{orderId}")
    public String redirectToVNPayPost(@PathVariable Long orderId, HttpServletRequest request) {
        requireCustomerAuthentication();
        String paymentUrl = vnPayService.createPaymentUrl(orderId, VNPayUtil.resolveClientIp(request));
        return "redirect:" + paymentUrl;
    }

    /**
     * Renders SCR-ST-13 (Payment & Order Result) when the customer's browser returns from VNPay.
     * Strictly presentation-only; authoritative state transitions occur exclusively via the IPN webhook.
     */
    @GetMapping("/vnpay-return")
    public String handleVNPayBrowserReturn(@RequestParam Map<String, String> params, Model model) {
        requireCustomerAuthentication();
        VNPayPaymentResultView resultView = vnPayService.evaluateBrowserReturn(params);
        model.addAttribute("paymentResult", resultView);
        return "payment/result";
    }

    private void requireCustomerAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Authentication is required to access payment endpoints.");
        }
        if (customerIdentity != null && (auth.getPrincipal() instanceof CustomUserDetails
                || auth.getPrincipal() instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser)) {
            customerIdentity.resolve(auth);
            return;
        }
        if (auth.getPrincipal() instanceof User domainUser) {
            CustomerIdentity.requireCustomer(domainUser);
            return;
        }
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        boolean isCustomer = authorities != null && authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_CUSTOMER".equals(role) || "CUSTOMER".equals(role));
        if (!isCustomer) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Only customers are permitted to access payment endpoints.");
        }
    }
}
