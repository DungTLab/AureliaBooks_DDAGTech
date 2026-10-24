package com.ddagtech.aureliabooks.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Configuration properties for VNPay Sandbox integration (UC11.1 / FND-04).
 * Secrets and merchant identifiers must be supplied via external configuration;
 * no hardcoded fallback credentials are permitted in source code.
 */
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
}
