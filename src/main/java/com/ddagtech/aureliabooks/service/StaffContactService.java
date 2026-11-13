package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.AdminStaffEmailChallengeView;
import com.ddagtech.aureliabooks.entity.User;
import java.util.Optional;

/**
 * Manages OTP verification challenges for internal staff self-service recovery email setup (UC28 GIAC-02).
 */
public interface StaffContactService {

    User loadInternalStaff(Long staffId);

    Optional<AdminStaffEmailChallengeView> findSelfEmailChallenge(Long staffId, String sessionId);

    AdminStaffEmailChallengeView startSelfEmailVerification(
            Long staffId,
            String sessionId,
            String targetEmail,
            String ipAddress
    );

    AdminStaffEmailChallengeView resendSelfEmailVerification(
            Long staffId,
            String sessionId,
            String ipAddress
    );

    User verifySelfEmail(Long staffId, String sessionId, String otpCode, String ipAddress);
}
