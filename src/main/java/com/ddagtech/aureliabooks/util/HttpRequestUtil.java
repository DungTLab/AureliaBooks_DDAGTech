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
            String[] parts = xForwardedFor.split(",");
            for (String part : parts) {
                String candidate = part.trim();
                if (!candidate.isEmpty() && !"unknown".equalsIgnoreCase(candidate) && isValidIp(candidate)) {
                    return candidate;
                }
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

    private static final java.util.Set<String> CONFIGURED_TRUSTED_PROXIES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Registers an explicit trusted proxy IP address into the application proxy allowlist.
     *
     * @param proxyIp trusted proxy IPv4 or IPv6 address
     */
    public static void registerTrustedProxy(String proxyIp) {
        if (proxyIp != null && !proxyIp.isBlank()) {
            CONFIGURED_TRUSTED_PROXIES.add(proxyIp.trim().toLowerCase());
        }
    }

    /**
     * Clears all registered trusted proxy IP addresses.
     */
    public static void clearTrustedProxies() {
        CONFIGURED_TRUSTED_PROXIES.clear();
    }

    /**
     * Checks if an IP address belongs to a trusted proxy:
     * - Loopback addresses (127.0.0.1, 127.0.0.0/8, ::1, 0:0:0:0:0:0:0:1, localhost)
     * - Explicitly registered proxy IP addresses in the proxy allowlist
     * Note: Private LAN IP addresses (10.x, 172.x, 192.168.x) are strictly NOT trusted by default
     * to eliminate internal LAN IP spoofing (CWE-290).
     *
     * @param ip IP address string
     * @return true if the IP belongs to a trusted proxy
     */
    public static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String trimmed = ip.trim().toLowerCase();

        // Loopback is always trusted for local reverse proxy (e.g. Nginx on same host)
        if ("127.0.0.1".equals(trimmed) || "::1".equals(trimmed) || "0:0:0:0:0:0:0:1".equals(trimmed)
                || trimmed.startsWith("127.") || "localhost".equals(trimmed)) {
            return true;
        }

        // Explicitly configured proxy allowlist
        return CONFIGURED_TRUSTED_PROXIES.contains(trimmed);
    }
}
