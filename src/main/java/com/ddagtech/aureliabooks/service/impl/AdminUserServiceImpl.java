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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

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
    private final ObjectMapper objectMapper;
    private final ReentrantLock adminMutationLock = new ReentrantLock();

    /**
     * Retrieves paginated internal staff users filtered by optional role name, active status, and search keyword.
     * Eliminates N+1 queries by relying on UserRepository join fetch.
     *
     * @param roleName optional role filter name
     * @param active optional active status filter
     * @param keyword optional search term matching name, email, or phone
     * @param pageable pagination parameters
     * @return page of UserSummary DTOs
     */
    @Override
    @Transactional(readOnly = true)
    public Page<UserSummary> list(String roleName, Boolean active, String keyword, Pageable pageable) {
        String filterRole = (roleName != null && !roleName.isBlank()) ? roleName.trim() : null;
        String filterKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        Page<User> staffPage = userRepository.findInternalStaff(filterRole, active, filterKeyword, pageable);
        return staffPage.map(user -> new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole() != null ? user.getRole().getId() : null,
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
     * @param ipAddress originating client IP address
     * @return generated persistent user ID
     */
    @Override
    @Transactional
    public Long create(Long adminId, UserCreateRequest request, String ipAddress) {
        String rawPhone = request.phone().trim();
        String canonicalPhone = rawPhone.startsWith("+84") ? "0" + rawPhone.substring(3) : rawPhone;

        if (userRepository.existsByEmail(request.email())) {
            throw new AppException(ErrorCode.USER_EXISTED, "Email đã được sử dụng trong hệ thống");
        }
        if (userRepository.existsByPhone(canonicalPhone)) {
            throw new AppException(ErrorCode.USER_EXISTED, "Số điện thoại đã được sử dụng trong hệ thống");
        }

        if (request.password() != null && request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mật khẩu không được vượt quá 72 byte theo chuẩn mã hóa BCrypt");
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
                .phone(canonicalPhone)
                .role(assignedRole)
                .authProvider(User.AuthProvider.LOCAL)
                .isActive(true)
                .build();

        User savedUser = userRepository.save(user);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", "USER_CREATE");
        details.put("actor_id", adminId);
        details.put("created_user_id", savedUser.getId());
        details.put("email", savedUser.getEmail());
        details.put("full_name", savedUser.getFullName());
        details.put("role", assignedRole.getRoleName());

        auditLogService.record(
                adminId,
                "USER_CREATE",
                "users",
                savedUser.getId(),
                toJson(details),
                ipAddress
        );

        log.info("Admin [{}] created staff user [{}] with role [{}] from IP [{}]",
                adminId, savedUser.getId(), assignedRole.getRoleName(), ipAddress);
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
     * @param ipAddress originating client IP address
     */
    @Override
    @Transactional
    public void setActive(Long adminId, Long userId, boolean active, String ipAddress) {
        if (adminId != null && adminId.equals(userId) && !active) {
            throw new AppException(ErrorCode.CANNOT_LOCK_SELF);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        boolean oldStatus = Boolean.TRUE.equals(user.getIsActive());

        if (!active && user.getRole() != null && "ROLE_ADMIN".equals(user.getRole().getRoleName())) {
            executeWithAdminLock(() -> {
                roleRepository.findByRoleNameForUpdate("ROLE_ADMIN");
                long activeAdmins = userRepository.countActiveAdmins();
                if (activeAdmins <= 1) {
                    throw new AppException(ErrorCode.CANNOT_REVOKE_LAST_ADMIN);
                }
                user.setIsActive(active);
                userRepository.save(user);
            });
        } else {
            user.setIsActive(active);
            userRepository.save(user);
        }

        if (!active) {
            expireUserSessions(userId);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", "USER_STATUS_TOGGLE");
        details.put("actor_id", adminId);
        details.put("target_user_id", userId);
        details.put("target_email", user.getEmail());
        details.put("before", Map.of("active", oldStatus));
        details.put("after", Map.of("active", active));

        auditLogService.record(
                adminId,
                "USER_STATUS_TOGGLE",
                "users",
                userId,
                toJson(details),
                ipAddress
        );

        log.info("Admin [{}] updated user [{}] active status to [{}] from IP [{}]", adminId, userId, active, ipAddress);
    }

    /**
     * Changes the internal RBAC role of a user.
     * Prevents demoting the last active administrator and prevents self-demotion from the admin role.
     * Terminates existing user sessions to force re-authentication with fresh GrantedAuthorities.
     *
     * @param adminId authenticated administrator user ID
     * @param userId target user ID to update
     * @param newRoleId target role ID to assign
     * @param ipAddress originating client IP address
     */
    @Override
    @Transactional
    public void changeRole(Long adminId, Long userId, Long newRoleId, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        Role newRole = roleRepository.findById(newRoleId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy vai trò mới"));

        if (!ALLOWED_INTERNAL_ROLES.contains(newRole.getRoleName())) {
            throw new AppException(ErrorCode.INVALID_ROLE_ASSIGNMENT);
        }

        String oldRoleName = user.getRole() != null ? user.getRole().getRoleName() : "UNKNOWN";
        boolean isCurrentlyAdmin = user.getRole() != null && "ROLE_ADMIN".equals(user.getRole().getRoleName());
        boolean isDemoting = isCurrentlyAdmin && !"ROLE_ADMIN".equals(newRole.getRoleName());

        if (isDemoting) {
            if (adminId != null && adminId.equals(userId)) {
                throw new AppException(ErrorCode.CANNOT_REVOKE_LAST_ADMIN, "Không thể tự thu hồi quyền Quản trị viên của chính mình");
            }
            executeWithAdminLock(() -> {
                roleRepository.findByRoleNameForUpdate("ROLE_ADMIN");
                long activeAdmins = userRepository.countActiveAdmins();
                if (activeAdmins <= 1) {
                    throw new AppException(ErrorCode.CANNOT_REVOKE_LAST_ADMIN);
                }
                user.setRole(newRole);
                userRepository.save(user);
            });
        } else {
            user.setRole(newRole);
            userRepository.save(user);
        }

        expireUserSessions(userId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", "USER_ROLE_UPDATE");
        details.put("actor_id", adminId);
        details.put("target_user_id", userId);
        details.put("target_email", user.getEmail());
        details.put("before", Map.of("role", oldRoleName));
        details.put("after", Map.of("role", newRole.getRoleName()));

        auditLogService.record(
                adminId,
                "USER_ROLE_UPDATE",
                "users",
                userId,
                toJson(details),
                ipAddress
        );

        log.info("Admin [{}] changed role for user [{}] to [{}] from IP [{}]",
                adminId, userId, newRole.getRoleName(), ipAddress);
    }

    /**
     * Serializes any payload object into a JSON string for audit storage.
     *
     * @param value payload object
     * @return serialized JSON string, or empty JSON object on serialization failure
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.error("Failed to serialize audit log details to JSON", ex);
            return "{}";
        }
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

    /**
     * Executes a critical administrative mutation under mutex protection.
     * If an active Spring transaction is detected, the lock release is deferred
     * via {@link TransactionSynchronization#afterCompletion(int)} until after the
     * transaction has fully committed or rolled back. This eliminates TOCTOU race
     * conditions across concurrent transactions.
     *
     * @param action critical logic to execute
     */
    private void executeWithAdminLock(Runnable action) {
        adminMutationLock.lock();
        boolean synchronizationRegistered = false;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        adminMutationLock.unlock();
                    }
                });
                synchronizationRegistered = true;
            }
            action.run();
        } finally {
            if (!synchronizationRegistered) {
                adminMutationLock.unlock();
            }
        }
    }
}
