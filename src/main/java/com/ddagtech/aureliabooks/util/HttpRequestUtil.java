package com.ddagtech.aureliabooks.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * Utility helper for inspecting HTTP servlet requests.
 * Detects whether an incoming request expects an AJAX/JSON API response or SSR HTML content,
 * and securely resolves client IP addresses by validating trusted proxy origins (CWE-290 mitigation).
 */
public final class HttpRequestUtil {

    private static final String XML_HTTP_REQUEST = "XMLHttpRequest";
    private static final String HEADER_REQUESTED_WITH = "X-Requested-With";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String API_PREFIX = "/api/";

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
    );

    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^[0-9a-fA-F:]{2,39}$"
    );

    private HttpRequestUtil() {
        // Prevent instantiation of utility class
    }

    /**
     * Determines whether the given HTTP request is an AJAX or REST API request.
     *
     * @param request incoming HttpServletRequest
     * @return true if the request expects or sends JSON or originated via XMLHttpRequest
     */
    public static boolean isAjaxOrApi(HttpServletRequest request) {
        if (request == null) {
            return false;
        }

        String requestedWith = request.getHeader(HEADER_REQUESTED_WITH);
        if (XML_HTTP_REQUEST.equalsIgnoreCase(requestedWith)) {
            return true;
        }

        String acceptHeader = request.getHeader(HEADER_ACCEPT);
        if (acceptHeader != null && acceptHeader.contains(APPLICATION_JSON)) {
            return true;
        }

        String contentType = request.getHeader(HEADER_CONTENT_TYPE);
        if (contentType != null && contentType.contains(APPLICATION_JSON)) {
            return true;
        }

        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(API_PREFIX);
    }

    /**
     * Resolves the originating client IP address.
     * Forwarded headers (X-Forwarded-For, X-Real-IP) are strictly evaluated ONLY IF the direct
     * socket connection originates from a trusted reverse proxy (loopback or private RFC 1918 subnet).
     * If the remote address is untrusted (direct public connection), forwarded headers are ignored
     * to eliminate IP spoofing vulnerabilities (CWE-290).
     *
     * @param request incoming HttpServletRequest
     * @return securely resolved client IP address or 127.0.0.1 default
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }

        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "127.0.0.1";
        }
        remoteAddr = remoteAddr.trim();

        // If the immediate connection does NOT originate from a trusted proxy,
        // treat it as a direct client connection and ignore all forwarded headers.
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        // Direct connection is from a trusted proxy -> inspect forwarded headers
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank() && !"unknown".equalsIgnoreCase(xForwardedFor.trim())) {
            String candidateIp = xForwardedFor.split(",")[0].trim();
            if (isValidIp(candidateIp)) {
                return candidateIp;
            }
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank() && !"unknown".equalsIgnoreCase(xRealIp.trim())) {
            String candidateIp = xRealIp.trim();
            if (isValidIp(candidateIp)) {
                return candidateIp;
            }
        }

        return remoteAddr;
    }

    /**
     * Validates whether an IP address string has a valid IPv4 or IPv6 syntax.
     *
     * @param ip IP string to validate
     * @return true if ip is a syntactically valid IPv4 or IPv6 address
     */
    public static boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String trimmed = ip.trim();
        if (IPV4_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        return trimmed.contains(":") && IPV6_PATTERN.matcher(trimmed).matches();
    }

    /**
     * Checks if an IP address belongs to a trusted proxy subnet:
     * - Loopback addresses (127.0.0.1, 127.0.0.0/8, ::1)
     * - RFC 1918 private subnets (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
     * - Link-local and IPv6 local subnets (169.254.0.0/16, fc00::/7, fe80::/10)
     *
     * @param ip IP address string
     * @return true if the IP belongs to a trusted internal/proxy subnet
     */
    public static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String trimmed = ip.trim().toLowerCase();

        // Loopback
        if ("127.0.0.1".equals(trimmed) || "::1".equals(trimmed) || "0:0:0:0:0:0:0:1".equals(trimmed)
                || trimmed.startsWith("127.") || "localhost".equals(trimmed)) {
            return true;
        }

        // RFC 1918: 10.0.0.0/8
        if (trimmed.startsWith("10.")) {
            return true;
        }

        // RFC 1918: 192.168.0.0/16
        if (trimmed.startsWith("192.168.")) {
            return true;
        }

        // RFC 3927: 169.254.0.0/16 (Link-Local)
        if (trimmed.startsWith("169.254.")) {
            return true;
        }

        // RFC 1918: 172.16.0.0/12 (172.16.x.x - 172.31.x.x)
        if (trimmed.startsWith("172.")) {
            String[] parts = trimmed.split("\\.");
            if (parts.length >= 2) {
                try {
                    int secondOctet = Integer.parseInt(parts[1]);
                    if (secondOctet >= 16 && secondOctet <= 31) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // Not a valid integer octet
                }
            }
        }

        // IPv6 Unique Local (fc00::/7 -> fc.. or fd..) and Link-Local (fe80::/10)
        return trimmed.startsWith("fc") || trimmed.startsWith("fd") || trimmed.startsWith("fe80");
    }
}
