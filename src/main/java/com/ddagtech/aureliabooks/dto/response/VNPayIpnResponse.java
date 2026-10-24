package com.ddagtech.aureliabooks.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable JSON response record returned by the merchant IPN webhook endpoint
 * to acknowledge processing status to the VNPay Sandbox gateway (UC11.1).
 */
public record VNPayIpnResponse(
        @JsonProperty("RspCode") String rspCode,
        @JsonProperty("Message") String message
) {
    public static VNPayIpnResponse of(String rspCode, String message) {
        return new VNPayIpnResponse(rspCode, message);
    }
}
