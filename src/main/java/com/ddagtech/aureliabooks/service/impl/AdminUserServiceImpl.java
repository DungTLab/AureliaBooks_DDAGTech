package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.dto.response.UserSummary;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.AdminUserService;
import com.ddagtech.aureliabooks.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Production implementation of {@link AdminUserService} managing internal accounts and RBAC (UC28).
 * Enforces business invariants: self-lock protection (BR-08-01), last active administrator retention,
 * internal role whitelist constraints, and immediate session invalidation upon deactivation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private static final Set<String> ALLOWED_INTERNAL_ROLES = Set.of(
            "ROLE_ADMIN",
            "ROLE_MANAGER",
            "ROLE_SALE_STAFF"
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionRegistry sessionRegistry;
    private final AuditLogService auditLogService;

    /**
     * Retrieves paginated internal staff users filtered by optional role name.
     * Eliminates N+1 queries by relying on UserRepository join fetch.
     *
     * @param roleName optional role filter name
     * @param pageable pagination parameters
     * @return page of UserSummary DTOs
     */
    @Override
    @Transactional(readOnly = true)
    public Page<UserSummary> list(String roleName, Pageable pageable) {
        String filterRole = (roleName != null && !roleName.isBlank()) ? roleName.trim() : null;
        Page<User> staffPage = userRepository.findInternalStaff(filterRole, pageable);
        return staffPage.map(user -> new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole() != null ? user.getRole().getRoleName() : null,
                user.getIsActive(),
                user.getCreatedAt()
        ));
    }

    /**
     * Provisions a new internal back-office staff account.
     * Validates uniqueness of email and phone, enforces internal role assignment whitelist,
     * hashes credentials with BCrypt, and appends a security audit entry.
     *
     * @param adminId authenticated administrator user ID performing the operation
     * @param request creation parameters DTO
     * @return generated persistent user ID
     */
    @Override
    @Transactional
    public Long create(Long adminId, UserCreateRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new AppException(ErrorCode.USER_EXISTED, "Email đã được sử dụng trong hệ thống");
        }
        if (userRepository.existsByPhone(request.phone())) {
            throw new AppException(ErrorCode.USER_EXISTED, "Số điện thoại đã được sử dụng trong hệ thống");
        }

        Role assignedRole = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy vai trò đã chọn"));

        if (!ALLOWED_INTERNAL_ROLES.contains(assignedRole.getRoleName())) {
            throw new AppException(ErrorCode.INVALID_ROLE_ASSIGNMENT);
        }

        User user = User.builder()
                .email(request.email().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .phone(request.phone().trim())
                .role(assignedRole)
                .authProvider(User.AuthProvider.LOCAL)
                .isActive(true)
                .build();

        User savedUser = userRepository.save(user);

        auditLogService.record(
                adminId,
                "USER_CREATE",
                "users",
                savedUser.getId(),
                "Created staff account: " + savedUser.getEmail() + " with role: " + assignedRole.getRoleName(),
                null
        );

        log.info("Admin [{}] created staff user [{}] with role [{}]",
                adminId, savedUser.getId(), assignedRole.getRoleName());
        return savedUser.getId();
    }

    /**
     * Toggles an internal user active status.
     * Enforces self-lock guard (BR-08-01) and ensures at least one active administrator remains.
     * When deactivated, all concurrent active HTTP sessions for the user are immediately expired.
     *
     * @param adminId authenticated administrator user ID
     * @param userId target user ID to update
     * @param active desired active state
     */
    @Override
    @Transactional
    public void setActive(Long adminId, Long userId, boolean active) {
        if (adminId != null && adminId.equals(userId) && !active) {
            throw new AppException(ErrorCode.CANNOT_LOCK_SELF);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        if (!active && user.getRole() != null && "ROLE_ADMIN".equals(user.getRole().getRoleName())) {
            long activeAdmins = userRepository.countActiveAdmins();
            if (activeAdmins <= 1) {
                throw new AppException(ErrorCode.CANNOT_REVOKE_LAST_ADMIN);
            }
        }

        user.setIsActive(active);
        userRepository.save(user);

        if (!active) {
            expireUserSessions(userId);
        }

        auditLogService.record(
                adminId,
                "USER_STATUS_TOGGLE",
                "users",
                userId,
                "Set active=" + active,
                null
        );

        log.info("Admin [{}] updated user [{}] active status to [{}]", adminId, userId, active);
    }

    /**
     * Changes the internal RBAC role of a user.
     * Prevents demoting the last active administrator and prevents self-demotion from the admin role.
     * Terminates existing user sessions to force re-authentication with fresh GrantedAuthorities.
     *
     * @param adminId authenticated administrator user ID
     * @param userId target user ID to update
     * @param newRoleId target role ID to assign
     */
    @Override
    @Transactional
    public void changeRole(Long adminId, Long userId, Long newRoleId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        Role newRole = roleRepository.findById(newRoleId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy vai trò mới"));

        if (!ALLOWED_INTERNAL_ROLES.contains(newRole.getRoleName())) {
            throw new AppException(ErrorCode.INVALID_ROLE_ASSIGNMENT);
        }

        boolean isCurrentlyAdmin = user.getRole() != null && "ROLE_ADMIN".equals(user.getRole().getRoleName());
        boolean isDemoting = isCurrentlyAdmin && !"ROLE_ADMIN".equals(newRole.getRoleName());

        if (isDemoting) {
            if (adminId != null && adminId.equals(userId)) {
                throw new AppException(ErrorCode.CANNOT_REVOKE_LAST_ADMIN, "Không thể tự thu hồi quyền Quản trị viên của chính mình");
            }
            long activeAdmins = userRepository.countActiveAdmins();
            if (activeAdmins <= 1) {
                throw new AppException(ErrorCode.CANNOT_REVOKE_LAST_ADMIN);
            }
        }

        user.setRole(newRole);
        userRepository.save(user);

        expireUserSessions(userId);

        auditLogService.record(
                adminId,
                "USER_ROLE_UPDATE",
                "users",
                userId,
                "Changed role to " + newRole.getRoleName(),
                null
        );

        log.info("Admin [{}] changed role for user [{}] to [{}]", adminId, userId, newRole.getRoleName());
    }

    /**
     * Invalidates all concurrent active HTTP sessions for the target user in the SessionRegistry.
     *
     * @param userId persistent user ID whose sessions should be expired
     */
    private void expireUserSessions(Long userId) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof CustomUserDetails userDetails && userId.equals(userDetails.getId())) {
                List<SessionInformation> sessions = sessionRegistry.getAllSessions(userDetails, false);
                for (SessionInformation session : sessions) {
                    session.expireNow();
                    log.info("Expired active session [{}] for deactivated/re-roled user [{}]",
                            session.getSessionId(), userId);
                }
            }
        }
    }
}
