package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.service.VNPayGatewayReconciliationClient;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Default VNPay Sandbox QueryDR reconciliation client for expired PENDING_PAYMENT orders (CRON-01 / V2.4 §5.2).
 *
 * Strictly enforces V2.4 Invariant:
 * - Expiration of the 15-minute window or missing local DB metadata NEVER proves that payment failed.
 * - Executes an authenticated server-to-server QueryDR HTTP POST against the configured VNPay API endpoint,
 *   verifies response signature, transaction reference, and amount, and classifies the outcome.
 * - Any missing configuration, network/timeout error, invalid signature, or non-conclusive gateway state
 *   returns {@link ReconciliationOutcome#INDETERMINATE} so the order remains pending.
 */
@Slf4j
@Setter
@Component
public class DefaultVNPayGatewayReconciliationClient implements VNPayGatewayReconciliationClient {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private VNPayConfig vnPayConfig;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;

    public DefaultVNPayGatewayReconciliationClient() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
    }

    @Autowired
    public DefaultVNPayGatewayReconciliationClient(VNPayConfig vnPayConfig, ObjectMapper objectMapper) {
        this.vnPayConfig = vnPayConfig;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
    }

    public DefaultVNPayGatewayReconciliationClient(
            VNPayConfig vnPayConfig,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.vnPayConfig = vnPayConfig;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build();
    }

    @Override
    public ReconciliationResult reconcileExpiredOrder(Order order) {
        if (order == null) {
            return ReconciliationResult.indeterminate("Order reference is null.");
        }
        if (order.getPaymentStatus() == Order.PaymentStatus.PAID) {
            return ReconciliationResult.paid(
                    order.getVnpayPayDate(),
                    order.getVnpayBankCode(),
                    "Order is already marked PAID."
            );
        }

        // Fail safe to INDETERMINATE when gateway credentials or QueryDR API endpoint are not configured (F01 / F03)
        if (vnPayConfig == null
                || vnPayConfig.getTmnCode() == null || vnPayConfig.getTmnCode().isBlank()
                || vnPayConfig.getHashSecret() == null || vnPayConfig.getHashSecret().isBlank()
                || vnPayConfig.getApiUrl() == null || vnPayConfig.getApiUrl().isBlank()) {
            return ReconciliationResult.indeterminate(
                    "VNPay QueryDR reconciliation credentials or API endpoint are not configured."
            );
        }

        String expectedTxnRef = (order.getVnpayTxnRef() != null && !order.getVnpayTxnRef().isBlank())
                ? order.getVnpayTxnRef().trim()
                : order.getOrderCode();
        if (expectedTxnRef == null || expectedTxnRef.isBlank()) {
            return ReconciliationResult.indeterminate("Order has no transaction reference or order code.");
        }

        try {
            String requestId = UUID.randomUUID().toString().replace("-", "");
            String version = (vnPayConfig.getVersion() != null && !vnPayConfig.getVersion().isBlank())
                    ? vnPayConfig.getVersion().trim()
                    : "2.1.0";
            String command = "querydr";
            String tmnCode = vnPayConfig.getTmnCode().trim();
            ZonedDateTime nowVn = ZonedDateTime.now(VNPayServiceImpl.VIETNAM_ZONE);
            ZonedDateTime txDateVn = order.getCreatedAt() != null
                    ? order.getCreatedAt().atZone(VNPayServiceImpl.VIETNAM_ZONE)
                    : nowVn;
            String transactionDate = txDateVn.format(VNPayServiceImpl.VNPAY_TIMESTAMP_FORMATTER);
            String createDate = nowVn.format(VNPayServiceImpl.VNPAY_TIMESTAMP_FORMATTER);
            String ipAddr = "127.0.0.1";
            String orderInfo = "QueryDR order " + order.getOrderCode();

            String requestHashData = String.join("|",
                    requestId, version, command, tmnCode, expectedTxnRef,
                    transactionDate, createDate, ipAddr, orderInfo
            );
            String secureHash = VNPayUtil.hmacSHA512(vnPayConfig.getHashSecret().trim(), requestHashData);

            Map<String, String> requestPayload = new LinkedHashMap<>();
            requestPayload.put("vnp_RequestId", requestId);
            requestPayload.put("vnp_Version", version);
            requestPayload.put("vnp_Command", command);
            requestPayload.put("vnp_TmnCode", tmnCode);
            requestPayload.put("vnp_TxnRef", expectedTxnRef);
            requestPayload.put("vnp_OrderInfo", orderInfo);
            requestPayload.put("vnp_TransactionDate", transactionDate);
            requestPayload.put("vnp_CreateDate", createDate);
            requestPayload.put("vnp_IpAddr", ipAddr);
            requestPayload.put("vnp_SecureHash", secureHash);

            String requestJson = objectMapper.writeValueAsString(requestPayload);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(vnPayConfig.getApiUrl().trim()))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() != 200 || httpResponse.body() == null || httpResponse.body().isBlank()) {
                return ReconciliationResult.indeterminate(
                        "Gateway QueryDR returned non-200 HTTP status or empty body: status=" + httpResponse.statusCode()
                );
            }

            Map<String, String> responseMap = objectMapper.readValue(httpResponse.body(), STRING_MAP_TYPE);
            if (responseMap == null || responseMap.isEmpty()) {
                return ReconciliationResult.indeterminate("Gateway QueryDR returned empty JSON object.");
            }

            // 1. Verify response cryptographic signature
            if (!verifyQueryDrResponseSignature(responseMap, vnPayConfig.getHashSecret().trim())) {
                return ReconciliationResult.indeterminate("Gateway QueryDR response signature verification failed.");
            }

            // 2. Verify transaction reference and merchant code match the order
            String respTxnRef = responseMap.get("vnp_TxnRef");
            if (respTxnRef == null || !expectedTxnRef.equals(respTxnRef.trim())) {
                return ReconciliationResult.indeterminate("Gateway QueryDR vnp_TxnRef mismatch.");
            }
            String respTmnCode = responseMap.get("vnp_TmnCode");
            if (respTmnCode != null && !respTmnCode.isBlank() && !tmnCode.equals(respTmnCode.trim())) {
                return ReconciliationResult.indeterminate("Gateway QueryDR vnp_TmnCode mismatch.");
            }

            // 3. Verify QueryDR API response code ("00" indicates the query succeeded and returned a transaction record)
            String queryResponseCode = responseMap.get("vnp_ResponseCode");
            if (!"00".equals(queryResponseCode)) {
                return ReconciliationResult.indeterminate(
                        "Gateway QueryDR did not return conclusive query code 00: vnp_ResponseCode=" + queryResponseCode
                );
            }

            // 4. Verify transaction amount matches order finalTotalAmount * 100
            if (order.getFinalTotalAmount() != null) {
                long expectedMinorAmount = order.getFinalTotalAmount()
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
                String rawRespAmount = responseMap.get("vnp_Amount");
                if (rawRespAmount == null || rawRespAmount.isBlank()) {
                    return ReconciliationResult.indeterminate("Gateway QueryDR response is missing vnp_Amount.");
                }
                long actualMinorAmount = Long.parseLong(rawRespAmount.trim());
                if (actualMinorAmount != expectedMinorAmount) {
                    return ReconciliationResult.indeterminate(
                            "Gateway QueryDR amount mismatch: expected=" + expectedMinorAmount + ", actual=" + actualMinorAmount
                    );
                }
            }

            // 5. Classify transaction status from QueryDR response
            String transactionStatus = responseMap.get("vnp_TransactionStatus");
            if (transactionStatus == null || transactionStatus.isBlank()) {
                return ReconciliationResult.indeterminate("Gateway QueryDR response is missing vnp_TransactionStatus.");
            }
            String cleanTxStatus = transactionStatus.trim();

            if ("00".equals(cleanTxStatus)) {
                return ReconciliationResult.paid(
                        responseMap.get("vnp_PayDate"),
                        responseMap.get("vnp_BankCode"),
                        "Verified PAID via VNPay QueryDR."
                );
            }
            if ("02".equals(cleanTxStatus)) {
                return ReconciliationResult.unpaidOrFailed(
                        "Verified terminal unpaid/failed transaction (vnp_TransactionStatus=02) via VNPay QueryDR."
                );
            }

            return ReconciliationResult.indeterminate(
                    "Gateway QueryDR transaction status is non-terminal or unknown: vnp_TransactionStatus=" + cleanTxStatus
            );
        } catch (Exception ex) {
            log.warn("VNPay QueryDR reconciliation failed for orderCode={}: {}", order.getOrderCode(), ex.getMessage());
            return ReconciliationResult.indeterminate("Gateway QueryDR error: " + ex.getMessage());
        }
    }

    /**
     * Verifies the HMAC-SHA512 signature on a VNPay QueryDR JSON response.
     * Supports both the official VNPay QueryDR v2.1.0 pipe-delimited formula and canonical sorted parameter signing.
     */
    public static boolean verifyQueryDrResponseSignature(Map<String, String> responseMap, String secretKey) {
        if (responseMap == null || secretKey == null || secretKey.isBlank()) {
            return false;
        }
        String receivedHash = responseMap.get("vnp_SecureHash");
        if (receivedHash == null || receivedHash.isBlank()) {
            return false;
        }

        String pipeData = String.join("|",
                nullToEmpty(responseMap.get("vnp_ResponseId")),
                nullToEmpty(responseMap.get("vnp_Command")),
                nullToEmpty(responseMap.get("vnp_ResponseCode")),
                nullToEmpty(responseMap.get("vnp_Message")),
                nullToEmpty(responseMap.get("vnp_TmnCode")),
                nullToEmpty(responseMap.get("vnp_TxnRef")),
                nullToEmpty(responseMap.get("vnp_Amount")),
                nullToEmpty(responseMap.get("vnp_BankCode")),
                nullToEmpty(responseMap.get("vnp_PayDate")),
                nullToEmpty(responseMap.get("vnp_TransactionNo")),
                nullToEmpty(responseMap.get("vnp_TransactionType")),
                nullToEmpty(responseMap.get("vnp_TransactionStatus")),
                nullToEmpty(responseMap.get("vnp_PromotionCode")),
                nullToEmpty(responseMap.get("vnp_PromotionAmount"))
        );
        String computedPipeHash = VNPayUtil.hmacSHA512(secretKey, pipeData);
        if (VNPayUtil.constantTimeEquals(computedPipeHash, receivedHash)) {
            return true;
        }

        return VNPayUtil.verifySignature(responseMap, secretKey);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
