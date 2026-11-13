package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.dto.response.RoleOption;
import com.ddagtech.aureliabooks.dto.response.UserSummary;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Stateless mapper for internal back-office User and Role conversions (UC28).
 */
@Component
public class AdminUserMapper {

    public static UserSummary toSummary(User user) {
        if (user == null) {
            return null;
        }
        String roleName = user.getRole() != null ? user.getRole().getRoleName() : null;
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole() != null ? user.getRole().getId() : null,
                roleName,
                user.getIsActive(),
                user.getCreatedAt(),
                user.isEmailVerified(),
                user.isPhoneVerified()
        );
    }

    public User toInternalStaffEntity(UserCreateRequest req, String encodedPassword, Role assignedRole) {
        if (req == null) {
            return null;
        }
        String canonicalPhone = com.ddagtech.aureliabooks.service.impl.ContactOtpEngine.normalizePhone(req.phone());
        return User.builder()
                .email(com.ddagtech.aureliabooks.service.impl.ContactOtpEngine.normalizeEmail(req.email()))
                .passwordHash(encodedPassword)
                .fullName(req.fullName() != null ? req.fullName().trim() : null)
                .phone(canonicalPhone)
                .role(assignedRole)
                .authProvider(User.AuthProvider.LOCAL)
                .isActive(true)
                .emailVerified(false)
                .phoneVerified(false)
                .build();
    }

    public RoleOption toRoleOption(Role role) {
        if (role == null) {
            return null;
        }
        return new RoleOption(role.getId(), role.getRoleName(), role.getDescription());
    }
}
