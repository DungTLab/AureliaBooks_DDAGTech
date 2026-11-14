package com.ddagtech.aureliabooks.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Configuration properties for VNPay Sandbox integration (UC11.1 / FND-04).
 * Secrets and merchant identifiers must be supplied via external configuration;
 * no hardcoded fallback credentials are permitted in source code.
 */
@Slf4j
@Getter
@Setter
@Configuration
public class VNPayConfig {

    @Value("${vnpay.tmn-code:}")
    private String tmnCode;

    @Value("${vnpay.hash-secret:}")
    private String hashSecret;

    @Value("${vnpay.pay-url:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}")
    private String payUrl;

    @Value("${vnpay.return-url:http://localhost:8080/payment/vnpay-return}")
    private String returnUrl;

    @Value("${vnpay.api-url:https://sandbox.vnpayment.vn/merchant_webapi/api/transaction}")
    private String apiUrl;

    @Value("${vnpay.version:2.1.0}")
    private String version;

    @Value("${vnpay.command:pay}")
    private String command;

    @Value("${vnpay.order-type:other}")
    private String orderType;

    @Value("${vnpay.expire-minutes:15}")
    private int expireMinutes;

    /**
     * Checks whether mandatory VNPay merchant credentials and HTTPS gateway URL are configured.
     *
     * @return true if tmnCode, hashSecret, and HTTPS payUrl are present and non-blank
     */
    public boolean isConfigured() {
        return tmnCode != null && !tmnCode.isBlank()
                && hashSecret != null && !hashSecret.isBlank()
                && payUrl != null && !payUrl.isBlank()
                && payUrl.trim().toLowerCase(Locale.ROOT).startsWith("https://");
    }

    /**
     * Inspects the configuration for values that are almost certainly wrong on a public deployment
     * (loopback return URL, or the sandbox gateway behind a public return URL). Secrets are never included.
     *
     * @return human-readable warnings; empty when VNPay is unconfigured or the configuration looks sane
     */
    public List<String> findConfigurationWarnings() {
        List<String> warnings = new ArrayList<>();
        if (tmnCode == null || tmnCode.isBlank() || hashSecret == null || hashSecret.isBlank()) {
            return warnings;
        }
        String normalizedReturnUrl = returnUrl == null ? "" : returnUrl.trim().toLowerCase(Locale.ROOT);
        boolean loopbackReturn = normalizedReturnUrl.contains("localhost") || normalizedReturnUrl.contains("127.0.0.1");
        if (loopbackReturn) {
            warnings.add("vnpay.return-url points to localhost; VNPay cannot redirect customers back to a local address.");
        }
        boolean sandboxGateway = payUrl != null && payUrl.toLowerCase(Locale.ROOT).contains("sandbox.vnpayment.vn");
        if (sandboxGateway && !loopbackReturn) {
            warnings.add("vnpay.pay-url targets the sandbox gateway while return-url is public; confirm this is not production.");
        }
        return warnings;
    }

    /** Logs configuration warnings once at startup so misconfigured deployments are visible. */
    @PostConstruct
    void logConfigurationWarnings() {
        findConfigurationWarnings().forEach(warning -> log.warn("VNPay configuration: {}", warning));
    }
}
