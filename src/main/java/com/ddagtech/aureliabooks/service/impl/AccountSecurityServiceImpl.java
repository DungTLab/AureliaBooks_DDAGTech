package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.PasswordChangeRequest;
import com.ddagtech.aureliabooks.dto.response.ContactSettingsView;
import com.ddagtech.aureliabooks.exception.AppException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.session.SessionInformation;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.mapper.UserMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.security.*;
import com.ddagtech.aureliabooks.service.*;
import java.time.LocalDate;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

/** Transaction boundaries for registration, recovery and Google sign-in account linking. */
@Service
@RequiredArgsConstructor
public class AccountSecurityServiceImpl implements AccountSecurityService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwords;
    private final LoginAttemptService attempts;
    private final SessionRegistry sessions;
    private final AuditLogService audit;

    @Override
    @Transactional(readOnly = true)
    public ContactSettingsView contactSettings(Long id) {
        return UserMapper.toContactSettings(customer(id));
    }

    public User customer(Long id) {
        var u = users.findById(id).orElseThrow();
        CustomerIdentity.requireCustomer(u);
        return u;
    }

    public void reauthenticate(Long id, String password, boolean googleProof) {
        var u = customer(id);
        if (u.getPasswordHash() == null) {
            if (!googleProof) throw new IllegalArgumentException("Vui lòng xác thực lại bằng Google.");
            return;
        }
        attempts.authenticate(id, () -> {
            if (
                password == null || !passwords.matches(password, u.getPasswordHash())
            ) throw new BadCredentialsException("Mật khẩu hiện tại không đúng.");
            return true;
        });
    }

    @Transactional
    public void register(Draft draft, String ip) {
        if (users.existsByPhone(draft.phone())) throw new IllegalArgumentException("Số điện thoại đã được đăng ký.");
        var role = roles.findByRoleName("ROLE_CUSTOMER").orElseThrow();
        var u = users.saveAndFlush(
            User.builder()
                .phone(draft.phone())
                .phoneVerified(true)
                .fullName(draft.fullName())
                .dob(draft.dob())
                .gender(draft.gender())
                .passwordHash(draft.passwordHash())
                .role(role)
                .build()
        );
        audit.record(u.getId(), "ACCOUNT_REGISTERED", "users", u.getId(), "{\"phone_verified\":true}", ip);
    }

        @Override
    @Transactional
    public User reset(Long id, String oldHash, String channel, String recipient, String newHash, String ip) {
        var u = users.findOwnerForUpdate(id).orElseThrow();
        if (
            !Boolean.TRUE.equals(u.getIsActive()) ||
            u.getPasswordHash() == null ||
            !Objects.equals(oldHash, u.getPasswordHash()) ||
            !("EMAIL".equals(channel)
                ? u.isEmailVerified() && Objects.equals(recipient, u.getEmail())
                : u.isPhoneVerified() && Objects.equals(recipient, u.getPhone()))
        ) throw new IllegalArgumentException("Yêu cầu không còn hiệu lực. Vui lòng bắt đầu lại.");
        u.setPasswordHash(newHash);
        User saved = users.saveAndFlush(u);
        if (attempts != null) {
            attempts.resetAttempts(id);
        }
        audit.record(id, "PASSWORD_RESET", "users", id, "{}", ip);
        expireAfterCommit(saved);
        return saved;
    }

    @Override
    @Transactional
    public User completeGoogleLink(Long id, String sub, String mail, String channel, String recipient, String ip) {
        var user = users
            .findOwnerForUpdate(id)
            .orElseThrow(() -> new IllegalArgumentException("Tài khoản không còn tồn tại."));
        CustomerIdentity.requireCustomer(user);
        if (
            sub == null ||
            sub.isBlank() ||
            sub.length() > 100 ||
            mail == null ||
            !mail.equalsIgnoreCase(user.getEmail()) ||
            channel == null
        ) {
            throw new IllegalArgumentException("Thông tin tài khoản đã thay đổi. Vui lòng đăng nhập Google lại.");
        }
        boolean validProof = switch (channel) {
            case "AUTO", "EMAIL" -> user.isEmailVerified() && Objects.equals(recipient, user.getEmail());
            case "SMS" -> user.isPhoneVerified() &&
                user.getPhone() != null &&
                Objects.equals(recipient, user.getPhone());
            default -> false;
        };
        if (!validProof) {
            throw new IllegalArgumentException("Kênh xác minh đã thay đổi hoặc chưa được xác minh.");
        }
        var linked = users.findByGoogleIdentity(User.AuthProvider.GOOGLE, sub);
        if (linked.isPresent() && !id.equals(linked.get().getId())) {
            throw new IllegalArgumentException("Google này đã liên kết với tài khoản khác.");
        }
        if (user.getProviderId() != null) {
            if (!sub.equals(user.getProviderId())) {
                throw new IllegalArgumentException("Tài khoản đã liên kết với Google khác.");
            }
            return user;
        }
        user.setProviderId(sub);
        users.saveAndFlush(user);
        audit.record(id, "GOOGLE_LINKED", "users", id, "{}", ip);
        return user;
    }

    @Override
    @Transactional
    public User changeStaffPassword(Long staffId, PasswordChangeRequest request, String currentSessionId, String clientIp) {
        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Dữ liệu đổi mật khẩu không hợp lệ");
        }
        User staff = users.findById(staffId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        if (staff.getRole() == null || "ROLE_CUSTOMER".equals(staff.getRole().getRoleName())) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Chỉ tài khoản nhân sự mới có thể đổi mật khẩu tại đây");
        }

        if (staff.getPasswordHash() == null || !passwords.matches(request.currentPassword(), staff.getPasswordHash())) {
            throw new AppException(ErrorCode.PASSWORD_NOT_MATCH, "Mật khẩu hiện tại không chính xác");
        }

        String newPassword = request.newPassword() != null ? request.newPassword().strip() : "";
        if (newPassword.isBlank() || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mật khẩu mới không hợp lệ hoặc vượt quá 72 byte");
        }

        if (request.confirmPassword() != null && !newPassword.equals(request.confirmPassword().strip())) {
            throw new AppException(ErrorCode.PASSWORD_NOT_MATCH, "Xác nhận mật khẩu mới không khớp");
        }

        if (passwords.matches(newPassword, staff.getPasswordHash())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mật khẩu mới không được trùng với mật khẩu hiện tại");
        }

        staff.setPasswordHash(passwords.encode(newPassword));
        User savedStaff = users.saveAndFlush(staff);
        if (attempts != null) {
            attempts.resetAttempts(staffId);
        }

        audit.record(
                staffId,
                "STAFF_PASSWORD_CHANGED",
                "users",
                staffId,
                "{\"action\":\"CHANGE_PASSWORD\",\"role\":\"" + staff.getRole().getRoleName() + "\"}",
                clientIp
        );

        expireOtherSessionsAfterCommit(staffId, currentSessionId);

        return savedStaff;
    }

    private void expireOtherSessionsAfterCommit(Long userId, String currentSessionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            expireOtherSessionsNow(userId, currentSessionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    expireOtherSessionsNow(userId, currentSessionId);
                }
            }
        );
    }

    private void expireOtherSessionsNow(Long userId, String currentSessionId) {
        for (Object p : sessions.getAllPrincipals()) {
            if (p instanceof CustomUserDetails local && userId.equals(local.getId())) {
                sessions.getAllSessions(p, false).stream()
                    .filter(s -> currentSessionId == null || !currentSessionId.equals(s.getSessionId()))
                    .forEach(SessionInformation::expireNow);
            }
        }
    }

    private void expireAfterCommit(User u) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (Object p : sessions.getAllPrincipals()) {
                        boolean same =
                            (p instanceof CustomUserDetails local && u.getId().equals(local.getId())) ||
                            (u.getProviderId() != null &&
                                p instanceof OAuth2User google &&
                                u.getProviderId().equals(google.getAttribute("sub")));
                        if (same) sessions.getAllSessions(p, false).forEach(s -> s.expireNow());
                    }
                }
            }
        );
    }
}
