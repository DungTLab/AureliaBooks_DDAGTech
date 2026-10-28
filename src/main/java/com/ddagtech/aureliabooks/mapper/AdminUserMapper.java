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
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole() != null ? user.getRole().getId() : null,
                user.getRole() != null ? user.getRole().getRoleName() : null,
                user.getIsActive(),
                user.getCreatedAt()
        );
    }

    public User toInternalStaffEntity(UserCreateRequest req, String encodedPassword, Role assignedRole) {
        if (req == null) {
            return null;
        }
        String rawPhone = req.phone() != null ? req.phone().trim() : "";
        String canonicalPhone = rawPhone.startsWith("+84") ? "0" + rawPhone.substring(3) : rawPhone;
        return User.builder()
                .email(req.email() != null ? req.email().trim().toLowerCase(Locale.ROOT) : null)
                .passwordHash(encodedPassword)
                .fullName(req.fullName() != null ? req.fullName().trim() : null)
                .phone(canonicalPhone)
                .role(assignedRole)
                .authProvider(User.AuthProvider.LOCAL)
                .isActive(true)
                .emailVerified(req.email() != null && !req.email().isBlank())
                .phoneVerified(!canonicalPhone.isBlank())
                .build();
    }

    public RoleOption toRoleOption(Role role) {
        if (role == null) {
            return null;
        }
        return new RoleOption(role.getId(), role.getRoleName(), role.getDescription());
    }
}
