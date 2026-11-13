package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.User;

/**
 * Transactional persistence boundary for OTP-verified internal staff email updates (UC28 GIAC-02).
 */
public interface StaffContactWriter {

    User applySelfVerifiedEmail(
            Long staffId,
            String expectedOldEmail,
            String expectedOldHash,
            String newEmail,
            String activeSessionId,
            String ipAddress
    );
}
