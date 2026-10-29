package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.request.BootstrapAdminRequest;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.AdminBootstrapService;
import com.ddagtech.aureliabooks.service.AuditLogService;
import jakarta.validation.Validator;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminBootstrapServiceImpl implements AdminBootstrapService {

    private final RoleRepository roles;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final AuditLogService audit;

    @Override
    @Transactional
    public boolean initialize(BootstrapAdminRequest request) {
        var role = roles
            .findByRoleNameForUpdate("ROLE_ADMIN")
            .orElseThrow(() -> new IllegalStateException("Initialize ROLE_ADMIN before admin bootstrap."));
        // Include inactive administrators: restarting must never bypass an intentional deactivation.
        if (users.existsByRole_RoleName("ROLE_ADMIN")) {
            return false;
        }
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new IllegalStateException(
                "Invalid bootstrap admin configuration: check email, password policy and optional phone."
            );
        }
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email) || (request.phone() != null && users.existsByPhone(request.phone()))) {
            throw new IllegalStateException(
                "Bootstrap contact conflicts with an existing account; no account was changed."
            );
        }
        User user = User.builder()
            .email(email)
            .phone(request.phone())
            .passwordHash(passwords.encode(request.password()))
            .fullName("Administrator")
            .role(role)
            .authProvider(User.AuthProvider.LOCAL)
            .isActive(true)
            .emailVerified(true)
            .phoneVerified(request.phone() != null && !request.phone().isBlank())
            .build();
        users.saveAndFlush(user);
        audit.record(null, "ADMIN_BOOTSTRAPPED", "users", user.getId(), "{\"source\":\"deployment\"}", null);
        return true;
    }
}
