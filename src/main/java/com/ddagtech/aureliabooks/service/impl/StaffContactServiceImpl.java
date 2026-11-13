package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.AdminStaffEmailOtpRequest;
import com.ddagtech.aureliabooks.dto.response.AdminStaffEmailChallengeView;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.ContactOtpDelivery;
import com.ddagtech.aureliabooks.service.StaffContactService;
import com.ddagtech.aureliabooks.service.StaffContactWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * In-memory OTP verification workflow for internal staff recovery email setup and
 * admin-assisted lost-channel email replacement (UC28 GIAC-02).
 */
@Slf4j
@Service
public class StaffContactServiceImpl implements StaffContactService {

    private static final int MAX_PENDING_FLOWS = 5_000;
    private static final int QUOTA_PER_USER = 5;
    private static final int QUOTA_PER_ACTOR = 10;
    private static final int QUOTA_PER_RECIPIENT = 5;
    private static final int QUOTA_PER_IP = 20;
    private static final int QUOTA_VERIFY = 10;

    private final UserRepository users;
    private final ContactOtpDelivery delivery;
    private final StaffContactWriter writer;
    private final ContactOtpEngine engine;

    private final Object[] locks = new Object[64];
    private final Map<ChallengeKey, PendingEmailOtp> pending = new HashMap<>();

    private record ChallengeKey(String mode, Long actorId, Long targetUserId) {
    }

    private static final class PendingEmailOtp {
        String sessionId;
        String recipient;
        String oldEmail;
        String oldHash;
        String reason;
        byte[] mac;
        Instant expiresAt;
        Instant lastSentAt;
        int attempts;
    }

    @Autowired
    public StaffContactServiceImpl(
            UserRepository users,
            ContactOtpDelivery delivery,
            StaffContactWriter writer
    ) {
        this(users, delivery, writer, Clock.systemUTC());
    }

    public StaffContactServiceImpl(
            UserRepository users,
            ContactOtpDelivery delivery,
            StaffContactWriter writer,
            Clock clock
    ) {
        this.users = users;
        this.delivery = delivery;
        this.writer = writer;
        this.engine = new ContactOtpEngine(clock);
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    @Override
    public User loadInternalStaff(Long staffId) {
        if (staffId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return requireActiveInternalStaff(staffId);
    }

    @Override
    public Optional<AdminStaffEmailChallengeView> findSelfEmailChallenge(Long staffId, String sessionId) {
        if (staffId == null || sessionId == null) {
            return Optional.empty();
        }
        ChallengeKey key = new ChallengeKey("SELF", staffId, staffId);
        return findChallengeView(key, sessionId);
    }

    @Override
    public AdminStaffEmailChallengeView startSelfEmailVerification(
            Long staffId,
            String sessionId,
            String targetEmail,
            String ipAddress
    ) {
        if (staffId == null || sessionId == null || sessionId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        ChallengeKey key = new ChallengeKey("SELF", staffId, staffId);
        return startChallenge(
                key,
                sessionId,
                staffId,
                staffId,
                targetEmail,
                null,
                "staff-self-user:" + staffId,
                ipAddress
        );
    }

    @Override
    public AdminStaffEmailChallengeView resendSelfEmailVerification(Long staffId, String sessionId, String ipAddress) {
        if (staffId == null || sessionId == null || sessionId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        ChallengeKey key = new ChallengeKey("SELF", staffId, staffId);
        return resendChallenge(key, sessionId, "staff-self-user:" + staffId, ipAddress);
    }

    @Override
    public User verifySelfEmail(Long staffId, String sessionId, String otpCode, String ipAddress) {
        if (staffId == null || sessionId == null || sessionId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        ChallengeKey key = new ChallengeKey("SELF", staffId, staffId);
        PendingEmailOtp verified = verifyChallengeCode(key, sessionId, otpCode, "staff-self-verify:" + staffId);
        return writer.applySelfVerifiedEmail(
                staffId,
                verified.oldEmail,
                verified.oldHash,
                verified.recipient,
                sessionId,
                ipAddress
        );
    }

    @Override
    public Optional<AdminStaffEmailChallengeView> findAdminEmailChallenge(
            Long adminId,
            String adminSessionId,
            Long targetUserId
    ) {
        if (adminId == null || targetUserId == null || adminSessionId == null || adminId.equals(targetUserId)) {
            return Optional.empty();
        }
        return findChallengeView(new ChallengeKey("ADMIN", adminId, targetUserId), adminSessionId);
    }

    @Override
    public AdminStaffEmailChallengeView startAdminEmailOtp(
            Long adminId,
            String adminSessionId,
            Long targetUserId,
            AdminStaffEmailOtpRequest request,
            String ipAddress
    ) {
        if (adminId == null || adminSessionId == null || adminSessionId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (targetUserId == null || adminId.equals(targetUserId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        if (request == null || !Boolean.TRUE.equals(request.identityVerified())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Vui lòng xác nhận đã kiểm tra danh tính nhân sự.");
        }
        ChallengeKey key = new ChallengeKey("ADMIN", adminId, targetUserId);
        return startChallenge(
                key,
                adminSessionId,
                adminId,
                targetUserId,
                request.email(),
                request.reason(),
                "admin-otp-actor:" + adminId,
                ipAddress
        );
    }

    @Override
    public AdminStaffEmailChallengeView resendAdminEmailOtp(
            Long adminId,
            String adminSessionId,
            Long targetUserId,
            String ipAddress
    ) {
        if (adminId == null || adminSessionId == null || adminSessionId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (targetUserId == null || adminId.equals(targetUserId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        ChallengeKey key = new ChallengeKey("ADMIN", adminId, targetUserId);
        return resendChallenge(key, adminSessionId, "admin-otp-actor:" + adminId, ipAddress);
    }

    @Override
    public User confirmAdminEmailOtp(
            Long adminId,
            String adminSessionId,
            Long targetUserId,
            String otpCode,
            String ipAddress
    ) {
        if (adminId == null || adminSessionId == null || adminSessionId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (targetUserId == null || adminId.equals(targetUserId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        ChallengeKey key = new ChallengeKey("ADMIN", adminId, targetUserId);
        PendingEmailOtp verified = verifyChallengeCode(
                key,
                adminSessionId,
                otpCode,
                "admin-otp-verify:" + adminId + ":" + targetUserId
        );
        return writer.applyAdminSupportedEmail(
                adminId,
                targetUserId,
                verified.oldEmail,
                verified.oldHash,
                verified.recipient,
                verified.reason,
                ipAddress
        );
    }

    private Optional<AdminStaffEmailChallengeView> findChallengeView(ChallengeKey key, String sessionId) {
        synchronized (lockFor(key)) {
            PendingEmailOtp flow = pending.get(key);
            if (flow == null || !Objects.equals(flow.sessionId, sessionId) || engine.isExpired(flow.expiresAt)) {
                return Optional.empty();
            }
            return Optional.of(toChallengeView(key.targetUserId(), flow));
        }
    }

    private AdminStaffEmailChallengeView startChallenge(
            ChallengeKey key,
            String sessionId,
            Long actorId,
            Long targetUserId,
            String rawEmail,
            String reason,
            String actorQuotaKey,
            String ipAddress
    ) {
        String normalizedEmail = ContactOtpEngine.normalizeEmail(rawEmail);
        if (!ContactOtpEngine.isValidEmail(normalizedEmail)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Email công việc không hợp lệ.");
        }

        synchronized (lockFor(key)) {
            User target = requireActiveInternalStaff(targetUserId);
            boolean emailChanged = !normalizedEmail.equalsIgnoreCase(target.getEmail() == null ? "" : target.getEmail());
            if (!emailChanged && target.isEmailVerified()) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Email này đã được xác minh.");
            }
            if (emailChanged && users.existsByEmail(normalizedEmail)) {
                throw new AppException(ErrorCode.USER_EXISTED);
            }

            PendingEmailOtp existing = pending.get(key);
            if (existing != null && engine.isInCooldown(existing.lastSentAt)) {
                throw new AppException(ErrorCode.OTP_RESEND_COOLDOWN);
            }

            purgeExpiredPending();
            if (pending.size() >= MAX_PENDING_FLOWS && !pending.containsKey(key)) {
                throw new AppException(ErrorCode.OTP_DELIVERY_FAILED, "Hệ thống đang bận, vui lòng thử lại sau.");
            }

            int actorLimit = actorId.equals(targetUserId) ? QUOTA_PER_USER : QUOTA_PER_ACTOR;
            enforceSendQuotas(actorQuotaKey, actorLimit, targetUserId, normalizedEmail, ipAddress);

            PendingEmailOtp flow = new PendingEmailOtp();
            flow.sessionId = sessionId;
            flow.recipient = normalizedEmail;
            flow.oldEmail = target.getEmail();
            flow.oldHash = target.getPasswordHash();
            flow.reason = reason;

            issueAndDeliver(key, flow, true);
            pending.put(key, flow);
            return toChallengeView(targetUserId, flow);
        }
    }

    private AdminStaffEmailChallengeView resendChallenge(
            ChallengeKey key,
            String sessionId,
            String actorQuotaKey,
            String ipAddress
    ) {
        synchronized (lockFor(key)) {
            PendingEmailOtp flow = pending.get(key);
            if (flow == null || engine.isExpired(flow.expiresAt)) {
                if (flow != null) {
                    pending.remove(key, flow);
                }
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
            if (!Objects.equals(flow.sessionId, sessionId)) {
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
            if (engine.isInCooldown(flow.lastSentAt)) {
                throw new AppException(ErrorCode.OTP_RESEND_COOLDOWN);
            }

            requireActiveInternalStaff(key.targetUserId());
            int actorLimit = key.actorId().equals(key.targetUserId()) ? QUOTA_PER_USER : QUOTA_PER_ACTOR;
            enforceSendQuotas(actorQuotaKey, actorLimit, key.targetUserId(), flow.recipient, ipAddress);

            issueAndDeliver(key, flow, false);
            return toChallengeView(key.targetUserId(), flow);
        }
    }

    private PendingEmailOtp verifyChallengeCode(
            ChallengeKey key,
            String sessionId,
            String otpCode,
            String verifyQuotaKey
    ) {
        synchronized (lockFor(key)) {
            PendingEmailOtp flow = pending.get(key);
            if (flow == null || engine.isExpired(flow.expiresAt)) {
                if (flow != null) {
                    pending.remove(key, flow);
                }
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
            if (!Objects.equals(flow.sessionId, sessionId)) {
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
            if (!engine.consumeAllQuotas(List.of(verifyQuotaKey), QUOTA_VERIFY)) {
                throw new AppException(ErrorCode.OTP_MAX_ATTEMPTS_EXCEEDED);
            }
            String otpContext = otpContext(key, flow);
            if (!engine.matches(otpContext, otpCode, flow.mac)) {
                flow.attempts++;
                if (flow.attempts >= ContactOtpEngine.MAX_ATTEMPTS) {
                    pending.remove(key, flow);
                    throw new AppException(ErrorCode.OTP_MAX_ATTEMPTS_EXCEEDED);
                }
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
            pending.remove(key, flow);
            return flow;
        }
    }

    private void issueAndDeliver(ChallengeKey key, PendingEmailOtp flow, boolean removeOnFailure) {
        ContactOtpEngine.OtpToken token = engine.issueCode(otpContext(key, flow), flow.mac);
        try {
            delivery.send("EMAIL", flow.recipient, token.code());
        } catch (RuntimeException ex) {
            if (removeOnFailure) {
                pending.remove(key, flow);
            }
            log.warn("Failed to deliver staff verification OTP to target user {}: {}", key.targetUserId(), ex.getMessage());
            throw new AppException(ErrorCode.OTP_DELIVERY_FAILED);
        }
        Instant now = engine.now();
        flow.mac = token.mac();
        flow.expiresAt = now.plus(ContactOtpEngine.TTL);
        flow.lastSentAt = now;
        flow.attempts = 0;
    }

    private void enforceSendQuotas(
            String actorQuotaKey,
            int actorLimit,
            Long targetUserId,
            String recipientEmail,
            String ipAddress
    ) {
        String safeIp = (ipAddress == null || ipAddress.isBlank()) ? "127.0.0.1" : ipAddress.trim();
        if (!engine.consumeAllQuotas(List.of(actorQuotaKey), actorLimit)
                || !engine.consumeAllQuotas(List.of("staff-target-user:" + targetUserId, "staff-email-target:" + recipientEmail), QUOTA_PER_USER)
                || !engine.consumeAllQuotas(List.of("staff-email-ip:" + safeIp), QUOTA_PER_IP)) {
            throw new AppException(ErrorCode.OTP_MAX_ATTEMPTS_EXCEEDED, "Bạn đã yêu cầu gửi mã quá nhiều lần. Vui lòng thử lại sau 15 phút.");
        }
    }

    private String otpContext(ChallengeKey key, PendingEmailOtp flow) {
        return key.mode() + "|" + key.actorId() + "|" + key.targetUserId() + "|" + flow.sessionId + "|" + flow.recipient;
    }

    private User requireActiveInternalStaff(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        String roleName = user.getRole() != null ? user.getRole().getRoleName() : null;
        if (roleName == null || !ContactOtpEngine.ALLOWED_INTERNAL_ROLES.contains(roleName)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        }
        return user;
    }

    private AdminStaffEmailChallengeView toChallengeView(Long targetUserId, PendingEmailOtp flow) {
        return new AdminStaffEmailChallengeView(
                targetUserId,
                ContactOtpEngine.maskEmail(flow.recipient),
                engine.remainingExpirySeconds(flow.expiresAt),
                engine.remainingCooldownSeconds(flow.lastSentAt)
        );
    }

    private void purgeExpiredPending() {
        pending.entrySet().removeIf(entry -> engine.isExpired(entry.getValue().expiresAt));
    }

    private Object lockFor(ChallengeKey key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }
}
