package com.ddagtech.aureliabooks.util;

import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cryptographic and query-string utility methods for VNPay Sandbox integration (UC11.1).
 * Implements RFC 2104 HMAC-SHA512 signing, lexicographical parameter canonicalization,
 * and constant-time checksum comparison to prevent timing side-channel attacks.
 */
public final class VNPayUtil {

    private static final String HMAC_SHA512_ALGORITHM = "HmacSHA512";

    /**
     * Thread-safe store of initiated PAY vnp_CreateDate timestamps keyed by order identity and vnp_TxnRef.
     * Used by JPA @PostLoad on Order to preserve exact PAY initiation timestamps across transactions
     * without altering the 21-table physical schema.sql contract (F10).
     */
    private static final Map<String, String> PAY_INITIATION_CREATE_DATES = new ConcurrentHashMap<>();

    private VNPayUtil() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Records the exact vnp_CreateDate (yyyyMMddHHmmss) sent during VNPay PAY initiation or retry (F10).
     *
     * @param orderId    database ID of the order (nullable in pure unit tests)
     * @param orderCode  unique order code
     * @param txnRef     vnp_TxnRef sent to VNPay
     * @param createDate 14-digit GMT+7 timestamp sent as vnp_CreateDate
     */
    public static void recordPayInitiation(Long orderId, String orderCode, String txnRef, String createDate) {
        if (txnRef == null || txnRef.isBlank() || createDate == null || createDate.isBlank()) {
            return;
        }
        String cleanTxnRef = txnRef.trim();
        String cleanCreateDate = createDate.trim();
        if (orderId != null) {
            PAY_INITIATION_CREATE_DATES.put(buildInitiationKey(orderId, orderCode, cleanTxnRef), cleanCreateDate);
        }
    }

    /**
     * Resolves a previously recorded vnp_CreateDate for a JPA-loaded Order entity.
     */
    public static String resolvePayInitiationCreateDate(Long orderId, String orderCode, String txnRef) {
        if (orderId == null || txnRef == null || txnRef.isBlank()) {
            return null;
        }
        return PAY_INITIATION_CREATE_DATES.get(buildInitiationKey(orderId, orderCode, txnRef.trim()));
    }

    /**
     * Clears recorded PAY initiation timestamps (for test isolation).
     */
    public static void clearPayInitiationMetadata() {
        PAY_INITIATION_CREATE_DATES.clear();
    }

    private static String buildInitiationKey(Long orderId, String orderCode, String txnRef) {
        return orderId + "|" + (orderCode != null ? orderCode.trim() : "") + "|" + txnRef;
    }

    /**
     * Computes a lowercase hexadecimal HMAC-SHA512 signature for the given data payload.
     *
     * @param key  secret key assigned by VNPay
     * @param data canonicalized query string payload
     * @return 128-character lowercase hex digest
     */
    public static String hmacSHA512(String key, String data) {
        if (key == null || data == null) {
            throw new IllegalArgumentException("HMAC key and data must not be null.");
        }
        try {
            Mac hmac512 = Mac.getInstance(HMAC_SHA512_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA512_ALGORITHM);
            hmac512.init(secretKey);
            byte[] digest = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC-SHA512 signature.", ex);
        }
    }

    /**
     * Performs a constant-time equality check between two hexadecimal digests.
     *
     * @param expectedHex locally computed HMAC-SHA512 hex string
     * @param actualHex   received vnp_SecureHash hex string
     * @return true if both hashes match identically
     */
    public static boolean constantTimeEquals(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        byte[] expectedBytes = expectedHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actualHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    /**
     * Builds the canonical hash data string from VNPay parameters sorted ascending by key,
     * excluding vnp_SecureHash and vnp_SecureHashType and blank values.
     *
     * @param params raw VNPay parameter map
     * @return canonicalized string for HMAC-SHA512 signing
     */
    public static String buildHashData(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = new TreeMap<>(params);
        StringBuilder hashData = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || value.isBlank()) {
                continue;
            }
            if ("vnp_SecureHash".equalsIgnoreCase(key) || "vnp_SecureHashType".equalsIgnoreCase(key)) {
                continue;
            }
            if (!hashData.isEmpty()) {
                hashData.append('&');
            }
            hashData.append(URLEncoder.encode(key, StandardCharsets.US_ASCII))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.US_ASCII));
        }
        return hashData.toString();
    }

    /**
     * Builds the URL-encoded query string from sorted parameters.
     *
     * @param params parameter map
     * @return URL query string
     */
    public static String buildQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = new TreeMap<>(params);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || value.isBlank()) {
                continue;
            }
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(URLEncoder.encode(key, StandardCharsets.US_ASCII))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.US_ASCII));
        }
        return query.toString();
    }

    /**
     * Verifies the cryptographic integrity of incoming VNPay callback parameters.
     *
     * @param params    incoming query/form parameters including vnp_SecureHash
     * @param secretKey merchant secret key
     * @return true if the signature is present and valid
     */
    public static boolean verifySignature(Map<String, String> params, String secretKey) {
        if (params == null || secretKey == null || secretKey.isBlank()) {
            return false;
        }
        String receivedHash = params.get("vnp_SecureHash");
        if (receivedHash == null || receivedHash.isBlank()) {
            return false;
        }
        String hashData = buildHashData(params);
        if (hashData.isEmpty()) {
            return false;
        }
        String computedHash = hmacSHA512(secretKey, hashData);
        return constantTimeEquals(computedHash, receivedHash);
    }

    /**
     * Extracts the client IPv4 address from the HTTP servlet request, normalizing IPv6 loopback
     * addresses (::1 / 0:0:0:0:0:0:0:1) to 127.0.0.1 for VNPay Sandbox compatibility.
     *
     * @param request incoming HTTP servlet request
     * @return resolved IP address string
     */
    public static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        String candidateIp = null;
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            candidateIp = forwardedFor.split(",")[0].trim();
        } else {
            String remoteAddr = request.getRemoteAddr();
            if (remoteAddr != null && !remoteAddr.isBlank()) {
                candidateIp = remoteAddr.trim();
            }
        }
        if (candidateIp == null || candidateIp.isEmpty()
                || "0:0:0:0:0:0:0:1".equals(candidateIp)
                || "::1".equals(candidateIp)) {
            return "127.0.0.1";
        }
        return candidateIp;
    }
}
