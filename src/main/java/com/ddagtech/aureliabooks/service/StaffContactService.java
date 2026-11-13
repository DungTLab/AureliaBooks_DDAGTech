package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.AdminStaffEmailOtpRequest;
import com.ddagtech.aureliabooks.dto.response.AdminStaffEmailChallengeView;
import com.ddagtech.aureliabooks.entity.User;
import java.util.Optional;

/**
 * Manages OTP verification challenges for internal staff self-service recovery email setup
 * and admin-assisted lost-channel email replacement (UC28 GIAC-02).
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

    Optional<AdminStaffEmailChallengeView> findAdminEmailChallenge(
            Long adminId,
            String adminSessionId,
            Long targetUserId
    );

    AdminStaffEmailChallengeView startAdminEmailOtp(
            Long adminId,
            String adminSessionId,
            Long targetUserId,
            AdminStaffEmailOtpRequest request,
            String ipAddress
    );

    AdminStaffEmailChallengeView resendAdminEmailOtp(
            Long adminId,
            String adminSessionId,
            Long targetUserId,
            String ipAddress
    );

    User confirmAdminEmailOtp(
            Long adminId,
            String adminSessionId,
            Long targetUserId,
            String otpCode,
            String ipAddress
    );
}
