package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.AdminStaffEmailChallengeView;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.ContactOtpDelivery;
import com.ddagtech.aureliabooks.service.StaffContactService;
import com.ddagtech.aureliabooks.service.StaffContactWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Thread-safe in-memory OTP verification workflow for internal staff self-service recovery email setup (UC28 GIAC-02).
 * Uses a {@link ConcurrentHashMap}, immutable challenge snapshots, and per-user lock striping outside SMTP I/O.
 */
@Slf4j
@Service
public class StaffContactServiceImpl implements StaffContactService {

    private static final int MAX_PENDING_FLOWS = 5_000;
    private static final int QUOTA_PER_USER = 5;
    private static final int QUOTA_PER_IP = 20;
    private static final int QUOTA_VERIFY = 10;

    private final UserRepository users;
    private final ContactOtpDelivery delivery;
    private final StaffContactWriter writer;
    private final ContactOtpEngine engine;

    private final Object[] locks = new Object[64];
    private final ConcurrentMap<Long, PendingEmailOtp> pending = new ConcurrentHashMap<>();

    private record PendingEmailOtp(
            String flowId,
            String sessionId,
            String recipient,
            String oldEmail,
            String oldHash,
            byte[] mac,
            Instant expiresAt,
            Instant lastSentAt,
            int attempts,
            boolean sending
    ) {
        PendingEmailOtp withSending(boolean inProgress, Instant attemptTime) {
            return new PendingEmailOtp(
                    flowId,
                    sessionId,
                    recipient,
                    oldEmail,
                    oldHash,
                    mac,
                    expiresAt,
                    attemptTime,
                    attempts,
                    inProgress
            );
        }

        PendingEmailOtp withSentToken(byte[] newMac, Instant sentAt) {
            return new PendingEmailOtp(
                    flowId,
                    sessionId,
                    recipient,
                    oldEmail,
                    oldHash,
                    newMac,
                    sentAt.plus(ContactOtpEngine.TTL),
                    sentAt,
                    0,
                    false
            );
        }

        PendingEmailOtp withIncrementedAttempts() {
            return new PendingEmailOtp(
                    flowId,
                    sessionId,
                    recipient,
                    oldEmail,
                    oldHash,
                    mac,
                    expiresAt,
                    lastSentAt,
                    attempts + 1,
                    sending
            );
        }
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
        PendingEmailOtp flow = pending.get(staffId);
        if (flow == null
                || flow.sending()
                || flow.mac() == null
                || !Objects.equals(flow.sessionId(), sessionId)
                || engine.isExpired(flow.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(toChallengeView(staffId, flow));
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
        String normalizedEmail = ContactOtpEngine.normalizeEmail(targetEmail);
        if (!ContactOtpEngine.isValidEmail(normalizedEmail)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Email công việc không hợp lệ.");
        }

        PendingEmailOtp placeholder;
        ContactOtpEngine.OtpToken token;
        synchronized (lockFor(staffId)) {
            User target = requireActiveInternalStaff(staffId);
            boolean emailChanged = !normalizedEmail.equalsIgnoreCase(target.getEmail() == null ? "" : target.getEmail());
            if (!emailChanged && target.isEmailVerified()) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Email này đã được xác minh.");
            }
            if (emailChanged && users.existsByEmail(normalizedEmail)) {
                throw new AppException(ErrorCode.USER_EXISTED);
            }

            PendingEmailOtp existing = pending.get(staffId);
            if (existing != null && (existing.sending() || engine.isInCooldown(existing.lastSentAt()))) {
                throw new AppException(ErrorCode.OTP_RESEND_COOLDOWN);
            }

            purgeExpiredPending();
            if (pending.size() >= MAX_PENDING_FLOWS && !pending.containsKey(staffId)) {
                throw new AppException(ErrorCode.OTP_DELIVERY_FAILED, "Hệ thống đang bận, vui lòng thử lại sau.");
            }

            enforceSendQuotas(staffId, normalizedEmail, ipAddress);

            Instant now = engine.now();
            placeholder = new PendingEmailOtp(
                    UUID.randomUUID().toString(),
                    sessionId,
                    normalizedEmail,
                    target.getEmail(),
                    target.getPasswordHash(),
                    null,
                    now.plus(ContactOtpEngine.TTL),
                    now,
                    0,
                    true
            );
            byte[] previousMac = existing != null ? existing.mac() : null;
            token = engine.issueCode(otpContext(staffId, placeholder), previousMac);
            pending.put(staffId, placeholder);
        }

        return deliverAndPublish(staffId, placeholder, null, token);
    }

    @Override
    public AdminStaffEmailChallengeView resendSelfEmailVerification(Long staffId, String sessionId, String ipAddress) {
        if (staffId == null || sessionId == null || sessionId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        PendingEmailOtp current;
        PendingEmailOtp placeholder;
        ContactOtpEngine.OtpToken token;
        synchronized (lockFor(staffId)) {
            current = pending.get(staffId);
            if (current == null || (!current.sending() && engine.isExpired(current.expiresAt()))) {
                if (current != null) {
                    pending.remove(staffId, current);
                }
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
            if (!Objects.equals(current.sessionId(), sessionId)) {
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
            if (current.sending() || engine.isInCooldown(current.lastSentAt())) {
                throw new AppException(ErrorCode.OTP_RESEND_COOLDOWN);
            }

            requireActiveInternalStaff(staffId);
            enforceSendQuotas(staffId, current.recipient(), ipAddress);

            placeholder = current.withSending(true, engine.now());
            token = engine.issueCode(otpContext(staffId, placeholder), current.mac());
            pending.put(staffId, placeholder);
        }

        return deliverAndPublish(staffId, placeholder, current, token);
    }

    @Override
    public User verifySelfEmail(Long staffId, String sessionId, String otpCode, String ipAddress) {
        if (staffId == null || sessionId == null || sessionId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        PendingEmailOtp verified = verifyChallengeCode(staffId, sessionId, otpCode);
        return writer.applySelfVerifiedEmail(
                staffId,
                verified.oldEmail(),
                verified.oldHash(),
                verified.recipient(),
                sessionId,
                ipAddress
        );
    }

    private AdminStaffEmailChallengeView deliverAndPublish(
            Long staffId,
            PendingEmailOtp placeholder,
            PendingEmailOtp rollbackTarget,
            ContactOtpEngine.OtpToken token
    ) {
        // Perform external SMTP I/O outside per-user lock stripes so slow mail sends cannot block other accounts.
        try {
            delivery.send("EMAIL", placeholder.recipient(), token.code());
        } catch (RuntimeException ex) {
            synchronized (lockFor(staffId)) {
                if (rollbackTarget == null) {
                    pending.remove(staffId, placeholder);
                } else {
                    pending.replace(staffId, placeholder, rollbackTarget);
                }
            }
            log.warn("Failed to deliver staff verification OTP to target user {}: {}", staffId, ex.getMessage());
            throw new AppException(ErrorCode.OTP_DELIVERY_FAILED);
        }

        PendingEmailOtp ready = placeholder.withSentToken(token.mac(), engine.now());
        synchronized (lockFor(staffId)) {
            if (!pending.replace(staffId, placeholder, ready)) {
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
        }
        return toChallengeView(staffId, ready);
    }

    private PendingEmailOtp verifyChallengeCode(Long staffId, String sessionId, String otpCode) {
        synchronized (lockFor(staffId)) {
            PendingEmailOtp flow = pending.get(staffId);
            if (flow == null || flow.mac() == null || engine.isExpired(flow.expiresAt())) {
                if (flow != null && !flow.sending() && engine.isExpired(flow.expiresAt())) {
                    pending.remove(staffId, flow);
                }
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
            if (!Objects.equals(flow.sessionId(), sessionId)) {
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
            if (!engine.consumeAllQuotas(List.of("staff-self-verify:" + staffId), QUOTA_VERIFY)) {
                throw new AppException(ErrorCode.OTP_MAX_ATTEMPTS_EXCEEDED);
            }
            if (!engine.matches(otpContext(staffId, flow), otpCode, flow.mac())) {
                PendingEmailOtp updated = flow.withIncrementedAttempts();
                if (updated.attempts() >= ContactOtpEngine.MAX_ATTEMPTS) {
                    pending.remove(staffId, flow);
                    throw new AppException(ErrorCode.OTP_MAX_ATTEMPTS_EXCEEDED);
                }
                pending.replace(staffId, flow, updated);
                throw new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED);
            }
            pending.remove(staffId, flow);
            return flow;
        }
    }

    private void enforceSendQuotas(Long staffId, String recipientEmail, String ipAddress) {
        String safeIp = (ipAddress == null || ipAddress.isBlank()) ? "127.0.0.1" : ipAddress.trim();
        if (!engine.consumeAllQuotas(List.of("staff-self-user:" + staffId), QUOTA_PER_USER)
                || !engine.consumeAllQuotas(List.of("staff-target-user:" + staffId, "staff-email-target:" + recipientEmail), QUOTA_PER_USER)
                || !engine.consumeAllQuotas(List.of("staff-email-ip:" + safeIp), QUOTA_PER_IP)) {
            throw new AppException(ErrorCode.OTP_MAX_ATTEMPTS_EXCEEDED, "Bạn đã yêu cầu gửi mã quá nhiều lần. Vui lòng thử lại sau 15 phút.");
        }
    }

    private String otpContext(Long staffId, PendingEmailOtp flow) {
        return "SELF|" + staffId + "|" + staffId + "|" + flow.sessionId() + "|" + flow.recipient();
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
                ContactOtpEngine.maskEmail(flow.recipient()),
                engine.remainingExpirySeconds(flow.expiresAt()),
                engine.remainingCooldownSeconds(flow.lastSentAt())
        );
    }

    private void purgeExpiredPending() {
        pending.entrySet().removeIf(entry -> {
            PendingEmailOtp flow = entry.getValue();
            return flow != null && !flow.sending() && engine.isExpired(flow.expiresAt());
        });
    }

    private Object lockFor(Long staffId) {
        return locks[Math.floorMod(staffId.hashCode(), locks.length)];
    }
}
