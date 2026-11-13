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

    /**
     * Converts a persistent {@link User} entity into a read-only {@link UserSummary} projection.
     *
     * @param user persisted user entity (nullable)
     * @return immutable user summary DTO, or null when input is null
     */
    public static UserSummary toSummary(User user) {
        if (user == null) {
            return null;
        }
        String roleName = user.getRole() != null ? user.getRole().getRoleName() : null;
        boolean passwordResetAllowed = roleName != null && !"ROLE_CUSTOMER".equalsIgnoreCase(roleName);
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole() != null ? user.getRole().getId() : null,
                roleName,
                user.getIsActive(),
                user.getCreatedAt(),
                passwordResetAllowed
        );
    }

    /**
     * Builds a new active local internal staff {@link User} entity from admin creation input.
     * Preserves existing verification behavior without modifying account security policy.
     *
     * @param req validated internal user creation payload
     * @param encodedPassword BCrypt-encoded initial password hash
     * @param assignedRole resolved internal {@link Role} entity
     * @return new transient {@link User} instance, or null when request is null
     */
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

    /**
     * Converts a persistent {@link Role} entity into a selectable {@link RoleOption} DTO.
     *
     * @param role role entity (nullable)
     * @return role option DTO, or null when role is null
     */
    public RoleOption toRoleOption(Role role) {
        if (role == null) {
            return null;
        }
        return new RoleOption(role.getId(), role.getRoleName(), role.getDescription());
    }
}
