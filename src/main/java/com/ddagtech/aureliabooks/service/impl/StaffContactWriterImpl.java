package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.service.StaffContactWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional writer committing verified internal staff email updates under pessimistic row locking,
 * enforcing stale-state guards, recording clean audit logs, and expiring sessions post-commit (UC28 GIAC-02).
 */
@Service
@RequiredArgsConstructor
public class StaffContactWriterImpl implements StaffContactWriter {

    private final UserRepository users;
    private final SessionRegistry sessions;
    private final AuditLogService audit;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public User applySelfVerifiedEmail(
            Long staffId,
            String expectedOldEmail,
            String expectedOldHash,
            String newEmail,
            String activeSessionId,
            String ipAddress
    ) {
        User staff = lockAndVerifyInternalStaff(staffId, expectedOldEmail, expectedOldHash);
        String cleanEmail = ContactOtpEngine.normalizeEmail(newEmail);
        if (!ContactOtpEngine.isValidEmail(cleanEmail)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA);
        }

        boolean emailChanged = !Objects.equals(cleanEmail, staff.getEmail());
        if (emailChanged && users.existsByEmail(cleanEmail)) {
            throw new AppException(ErrorCode.USER_EXISTED);
        }

        staff.setEmail(cleanEmail);
        staff.setEmailVerified(true);
        User saved = users.saveAndFlush(staff);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", "INTERNAL_EMAIL_VERIFIED");
        details.put("staff_id", staffId);
        details.put("verified_email", cleanEmail);
        details.put("email_changed", emailChanged);
        details.put("verification_channel", "EMAIL");
        details.put("email_verified", true);
        audit.recordRequired(
                staffId,
                "INTERNAL_EMAIL_VERIFIED",
                "users",
                staffId,
                toJson(details),
                normalizeIp(ipAddress)
        );

        runAfterCommit(() -> expireUserSessions(staffId, activeSessionId));
        return saved;
    }

    private User lockAndVerifyInternalStaff(Long userId, String expectedOldEmail, String expectedOldHash) {
        User user = users.findOwnerForUpdate(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        String roleName = user.getRole() != null ? user.getRole().getRoleName() : null;
        if (roleName == null || !ContactOtpEngine.ALLOWED_INTERNAL_ROLES.contains(roleName)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!Objects.equals(expectedOldEmail, user.getEmail())
                || !Objects.equals(expectedOldHash, user.getPasswordHash())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Thông tin tài khoản đã thay đổi. Vui lòng thực hiện lại.");
        }
        return user;
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void expireUserSessions(Long targetUserId, String preserveSessionId) {
        if (targetUserId == null || sessions == null) {
            return;
        }
        for (Object principal : sessions.getAllPrincipals()) {
            if (principal instanceof CustomUserDetails details && targetUserId.equals(details.getId())) {
                for (SessionInformation info : sessions.getAllSessions(principal, false)) {
                    if (preserveSessionId == null || !preserveSessionId.equals(info.getSessionId())) {
                        info.expireNow();
                    }
                }
            }
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize staff email verification proof", ex);
        }
    }

    private static String normalizeIp(String ipAddress) {
        return (ipAddress == null || ipAddress.isBlank()) ? "127.0.0.1" : ipAddress.trim();
    }
}
