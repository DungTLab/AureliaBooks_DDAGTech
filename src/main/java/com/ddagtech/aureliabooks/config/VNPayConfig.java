package com.ddagtech.aureliabooks.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for VNPay Sandbox integration (UC11.1 / FND-04).
 * Secrets and endpoints are externalized via environment variables or application properties.
 */
@Getter
@Setter
@Configuration
public class VNPayConfig {

    @Value("${vnpay.tmn-code:AURELIA01}")
    private String tmnCode;

    @Value("${vnpay.hash-secret:SECRETKEY1234567890ABCDEF12345678}")
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
}
