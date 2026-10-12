package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

/** UC05 only; profile/password-change contracts remain separate. */
@Service
@RequiredArgsConstructor
public class RegistrationService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final Validator validator;

    @Transactional
    public Long registerLocal(RegisterRequest request) {
        if (!validator.validate(request).isEmpty()) throw new AppException(ErrorCode.INVALID_INPUT_DATA);
        if (users.existsByEmail(request.email()) || users.existsByPhone(request.phone())) {
            throw new AppException(ErrorCode.USER_EXISTED);
        }
        User account = User.builder().email(request.email()).phone(request.phone())
                .fullName(request.fullName()).dob(request.dob()).gender(request.gender())
                .passwordHash(encoder.encode(request.password())).authProvider(User.AuthProvider.LOCAL)
                .role(customerRole()).isActive(true).build();
        // Flush: SQL unique constraints remain authoritative for simultaneous requests.
        return users.saveAndFlush(account).getId();
    }

    @Transactional
    public User registerGoogle(String subject, String email, Boolean verified, String name, String picture) {
        if (!Boolean.TRUE.equals(verified) || subject == null || subject.isBlank() || subject.length() > 100) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Google chưa xác thực thông tin tài khoản. Vui lòng thử lại.");
        }
        String normalizedEmail = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        if (!validator.validateValue(RegisterRequest.class, "email", normalizedEmail).isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Email Google không hợp lệ.");
        }
        var existing = users.findByGoogleIdentity(User.AuthProvider.GOOGLE, subject);
        if (existing.isPresent()) {
            User account = existing.get();
            if (!Boolean.TRUE.equals(account.getIsActive())) throw new AppException(ErrorCode.ACCOUNT_LOCKED);
            // Stable identity: never rebind an existing subject by its current email.
            return account;
        }
        if (users.existsByEmail(normalizedEmail)) {
            throw new AppException(ErrorCode.USER_EXISTED,
                    "Email này đã có tài khoản. Vui lòng đăng nhập bằng phương thức đã đăng ký; tài khoản chưa được liên kết với Google.");
        }
        String displayName = name == null || name.isBlank() ? "Khách hàng Google" : name.strip();
        if (displayName.length() > 100) displayName = displayName.substring(0, 100);
        String avatar = picture != null && picture.startsWith("https://") && picture.length() <= 500 ? picture : null;
        return users.saveAndFlush(User.builder().email(normalizedEmail).fullName(displayName)
                .authProvider(User.AuthProvider.GOOGLE).providerId(subject).avatarUrl(avatar)
                .role(customerRole()).isActive(true).build());
    }

    private Role customerRole() {
        return roles.findByRoleName("ROLE_CUSTOMER").orElseThrow(() ->
                new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION,
                        "Chưa thể tạo tài khoản. Vui lòng liên hệ quản trị viên."));
    }
}
