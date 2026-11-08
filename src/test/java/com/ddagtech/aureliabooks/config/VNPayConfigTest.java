package com.ddagtech.aureliabooks.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VNPayConfigTest {

    private static final String SANDBOX_PAY_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private static final String LIVE_PAY_URL = "https://pay.vnpay.vn/vpcpay.html";

    private static VNPayConfig config(String payUrl, String returnUrl, String tmnCode, String secret) {
        VNPayConfig config = new VNPayConfig();
        config.setPayUrl(payUrl);
        config.setReturnUrl(returnUrl);
        config.setTmnCode(tmnCode);
        config.setHashSecret(secret);
        return config;
    }

    @Test
    @DisplayName("Unconfigured VNPay (no merchant credentials) produces no warnings")
    void unconfigured_NoWarnings() {
        VNPayConfig config = config(SANDBOX_PAY_URL, "http://localhost:8080/payment/vnpay-return", "", "");

        assertThat(config.findConfigurationWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Localhost return URL with real credentials is flagged")
    void localhostReturnUrl_IsFlagged() {
        VNPayConfig config = config(LIVE_PAY_URL, "http://localhost:8080/payment/vnpay-return", "TMN", "secret");

        List<String> warnings = config.findConfigurationWarnings();

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("return-url").contains("localhost");
    }

    @Test
    @DisplayName("Sandbox pay URL combined with a public return URL is flagged")
    void sandboxPayUrlWithPublicReturnUrl_IsFlagged() {
        VNPayConfig config = config(SANDBOX_PAY_URL, "https://aurelia.example.vn/payment/vnpay-return", "TMN", "secret");

        List<String> warnings = config.findConfigurationWarnings();

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("pay-url").contains("sandbox");
    }

    @Test
    @DisplayName("Live gateway with HTTPS public return URL is clean")
    void liveConfiguration_NoWarnings() {
        VNPayConfig config = config(LIVE_PAY_URL, "https://aurelia.example.vn/payment/vnpay-return", "TMN", "secret");

        assertThat(config.findConfigurationWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Warnings never contain the merchant secret")
    void warnings_DoNotLeakSecrets() {
        VNPayConfig config = config(SANDBOX_PAY_URL, "http://127.0.0.1:8080/x", "TMN-CODE-1", "TOP-SECRET-VALUE");

        assertThat(String.join(" ", config.findConfigurationWarnings()))
                .doesNotContain("TOP-SECRET-VALUE");
    }
}
