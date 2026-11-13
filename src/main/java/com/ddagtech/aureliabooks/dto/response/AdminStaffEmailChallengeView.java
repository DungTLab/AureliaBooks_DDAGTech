package com.ddagtech.aureliabooks.dto.response;

/**
 * Immutable UI view of an active internal staff email OTP challenge.
 * Never exposes the raw OTP code.
 */
public record AdminStaffEmailChallengeView(
        Long targetUserId,
        String maskedEmail,
        long expiresSeconds,
        long resendSeconds
) {
}
