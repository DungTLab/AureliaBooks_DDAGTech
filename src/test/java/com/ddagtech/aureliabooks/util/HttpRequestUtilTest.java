package com.ddagtech.aureliabooks.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for {@link HttpRequestUtil}.
 * Verifies trusted proxy boundary enforcement, IP spoofing mitigation (CWE-290),
 * and AJAX/API request detection.
 */
@ExtendWith(MockitoExtension.class)
class HttpRequestUtilTest {

    @Mock
    private HttpServletRequest request;

    @Test
    @DisplayName("getClientIp() should ignore forwarded headers from untrusted direct public client")
    void testDirectUntrustedClient_IgnoresForwardedHeaders() {
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");

        String resolvedIp = HttpRequestUtil.getClientIp(request);

        assertThat(resolvedIp).isEqualTo("192.0.2.10");
    }

    @Test
    @DisplayName("getClientIp() should trust X-Forwarded-For when connection originates from loopback proxy")
    void testTrustedProxyLoopback_ParsesXForwardedFor() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.123");

        String resolvedIp = HttpRequestUtil.getClientIp(request);

        assertThat(resolvedIp).isEqualTo("203.0.113.123");
    }

    @Test
    @DisplayName("getClientIp() should extract leftmost client IP from multi-hop X-Forwarded-For")
    void testTrustedProxyPrivateSubnet_ParsesFirstHopInMultiHopHeader() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.123, 10.0.0.2");

        String resolvedIp = HttpRequestUtil.getClientIp(request);

        assertThat(resolvedIp).isEqualTo("203.0.113.123");
    }

    @Test
    @DisplayName("getClientIp() should fallback to X-Real-IP when X-Forwarded-For is missing")
    void testTrustedProxy_FallbackToXRealIp() {
        when(request.getRemoteAddr()).thenReturn("192.168.1.50");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.42");

        String resolvedIp = HttpRequestUtil.getClientIp(request);

        assertThat(resolvedIp).isEqualTo("198.51.100.42");
    }

    @Test
    @DisplayName("getClientIp() should reject syntactically invalid forwarded IP and fallback to remoteAddr")
    void testTrustedProxy_MalformedForwardedIpFallsBackToRemoteAddr() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("<script>malicious</script>");
        when(request.getHeader("X-Real-IP")).thenReturn(null);

        String resolvedIp = HttpRequestUtil.getClientIp(request);

        assertThat(resolvedIp).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("getClientIp() should handle empty or comma-only X-Forwarded-For headers without throwing exception")
    void testTrustedProxy_CommaOnlyForwardedHeader_FallsBackToRemoteAddr() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(",");
        when(request.getHeader("X-Real-IP")).thenReturn(null);

        String resolvedIp = HttpRequestUtil.getClientIp(request);

        assertThat(resolvedIp).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("getClientIp() should handle multi-comma and empty tokens in X-Forwarded-For")
    void testTrustedProxy_MultiCommaWithValidToken_ReturnsValidIp() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(" , 203.0.113.123 , ");

        String resolvedIp = HttpRequestUtil.getClientIp(request);

        assertThat(resolvedIp).isEqualTo("203.0.113.123");
    }

    @Test
    @DisplayName("getClientIp() should return 127.0.0.1 default when request is null")
    void testNullRequest_ReturnsDefaultLocalhost() {
        String resolvedIp = HttpRequestUtil.getClientIp(null);

        assertThat(resolvedIp).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("isAjaxOrApi() should detect XMLHttpRequest, JSON Accept header, and /api/ URI prefix")
    void testIsAjaxOrApi_Detections() {
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
        assertThat(HttpRequestUtil.isAjaxOrApi(request)).isTrue();

        when(request.getHeader("X-Requested-With")).thenReturn(null);
        when(request.getHeader("Accept")).thenReturn("application/json");
        assertThat(HttpRequestUtil.isAjaxOrApi(request)).isTrue();

        when(request.getHeader("Accept")).thenReturn(null);
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        assertThat(HttpRequestUtil.isAjaxOrApi(request)).isTrue();

        when(request.getHeader("Content-Type")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/users");
        assertThat(HttpRequestUtil.isAjaxOrApi(request)).isTrue();

        when(request.getRequestURI()).thenReturn("/admin/users");
        assertThat(HttpRequestUtil.isAjaxOrApi(request)).isFalse();

        assertThat(HttpRequestUtil.isAjaxOrApi(null)).isFalse();
    }
}
