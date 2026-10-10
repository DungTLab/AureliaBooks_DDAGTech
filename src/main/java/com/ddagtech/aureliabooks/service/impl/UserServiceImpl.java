package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.ProfileView;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.*;
import com.ddagtech.aureliabooks.service.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository users;
    private final RegistrationService registration;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final AvatarStorage avatars;
    private final SessionRegistry sessions;

    public Long register(RegisterRequest request) { return registration.registerLocal(request); }
    @Transactional(readOnly = true)
    public ProfileView viewProfile(Long id) {
        User u = users.findById(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        CustomerIdentity.requireCustomer(u);
        return new ProfileView(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getDob(),
                u.getGender().name(), u.getAvatarUrl(), canCompletePhone(u), u.getPasswordHash() != null,
                canCompleteDob(u));
    }
    @Transactional
    public void updateProfile(Long id, ProfileUpdateRequest request) {
        validate(request);
        User u = owner(id);
        if (request.dob() != null && !Objects.equals(request.dob(), u.getDob())) {
            if (!canCompleteDob(u)) throw new AppException(ErrorCode.DOB_IMMUTABLE);
            u.setDob(request.dob());
        }
        if (request.phone() != null && !Objects.equals(request.phone(), u.getPhone())) {
            if (!canCompletePhone(u)) throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Không thể thay đổi số điện thoại đã đăng ký.");
            if (users.existsByPhone(request.phone())) throw new AppException(ErrorCode.USER_EXISTED);
            u.setPhone(request.phone());
        }
        u.setFullName(request.fullName());
        u.setGender(request.gender());
        users.saveAndFlush(u); // Unique index is authoritative for simultaneous phone completion.
    }
    @Transactional
    public void changePassword(Long id, PasswordChangeRequest request) {
        validate(request);
        User u = owner(id);
        if (u.getPasswordHash() == null) throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                "Tài khoản Google chưa có mật khẩu. Vui lòng tiếp tục đăng nhập bằng Google.");
        if (!passwords.matches(request.currentPassword(), u.getPasswordHash()))
            throw new AppException(ErrorCode.PASSWORD_NOT_MATCH);
        u.setPasswordHash(passwords.encode(request.newPassword()));
        users.saveAndFlush(u);
        afterCommit(() -> sessions.getAllPrincipals().stream()
                .filter(p -> p instanceof CustomUserDetails local && id.equals(local.getId())
                        || p instanceof OidcUser google && Objects.equals(u.getProviderId(), google.getSubject()))
                .forEach(p -> sessions.getAllSessions(p, false).forEach(s -> s.expireNow())));
    }
    @Transactional
    public void updateAvatar(Long id, MultipartFile file) {
        User u = owner(id);
        String oldUrl = u.getAvatarUrl();
        String newUrl = avatars.store(file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                avatars.delete(status == STATUS_COMMITTED ? oldUrl : newUrl);
            }
        });
        u.setAvatarUrl(newUrl);
        users.saveAndFlush(u);
    }
    private User owner(Long id) {
        User u = users.findOwnerForUpdate(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        CustomerIdentity.requireCustomer(u); return u;
    }
    private boolean canCompletePhone(User u) {
        return u.getAuthProvider() == User.AuthProvider.GOOGLE && (u.getPhone() == null || u.getPhone().isBlank());
    }
    private boolean canCompleteDob(User u) {
        return u.getAuthProvider() == User.AuthProvider.GOOGLE && u.getDob() == null;
    }
    private void validate(Object request) {
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                violations.iterator().next().getMessage());
    }
    private void afterCommit(Runnable callback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { callback.run(); }
        });
    }
}
