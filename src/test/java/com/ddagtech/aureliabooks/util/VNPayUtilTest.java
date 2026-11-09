package com.ddagtech.aureliabooks.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VNPayUtilTest {

    private static final String SECRET = "unit-test-secret";

    @Test
    @DisplayName("resolveClientIp() uses only the first client IP of a multi-hop X-Forwarded-For")
    void resolveClientIp_MultiHopForwardedFor_ReturnsFirstClient() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.113.1.1, 10.0.0.1");

        assertThat(VNPayUtil.resolveClientIp(request)).isEqualTo("203.113.1.1");
    }

    @Test
    @DisplayName("Signature round-trips when vnp_OrderInfo has spaces and special characters")
    void verifySignature_OrderInfoWithSpacesAndSpecialChars_RoundTrips() {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Amount", "10000000");
        params.put("vnp_OrderInfo", "Thanh toan don hang AB-1 & test=ok +% é");
        params.put("vnp_TxnRef", "ORD1");

        String secureHash = VNPayUtil.hmacSHA512(SECRET, VNPayUtil.buildHashData(params));
        // Callback parameters arrive URL-decoded (Spring @RequestParam), plus the hash fields.
        Map<String, String> callback = new TreeMap<>(params);
        callback.put("vnp_SecureHash", secureHash);
        callback.put("vnp_SecureHashType", "HmacSHA512");

        assertThat(VNPayUtil.verifySignature(callback, SECRET)).isTrue();
    }

    @Test
    @DisplayName("Hash data and query string use identical encoding for every value")
    void buildHashData_MatchesQueryStringEncoding() {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_OrderInfo", "a b&c=d");
        params.put("vnp_TxnRef", "ORD1");

        assertThat(VNPayUtil.buildQueryString(params)).isEqualTo(VNPayUtil.buildHashData(params));
    }

    @Test
    @DisplayName("Tampering with a signed value invalidates the signature")
    void verifySignature_TamperedAmount_Fails() {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Amount", "10000000");
        params.put("vnp_TxnRef", "ORD1");
        params.put("vnp_SecureHash", VNPayUtil.hmacSHA512(SECRET, VNPayUtil.buildHashData(params)));
        params.put("vnp_Amount", "100");

        assertThat(VNPayUtil.verifySignature(params, SECRET)).isFalse();
    }
}
