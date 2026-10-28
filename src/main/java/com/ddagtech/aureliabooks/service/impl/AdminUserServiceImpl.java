package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.dto.response.RoleOption;
import com.ddagtech.aureliabooks.dto.response.UserSummary;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.AdminUserMapper;
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
import java.security.SecureRandom;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Production implementation of {@link AdminUserService} managing internal accounts and RBAC (UC28).
 * Enforces business invariants: self-lock protection (BR-08-01), last active administrator retention,
 * internal role whitelist constraints, and immediate session invalidation upon deactivation.
 */
@Slf4j
@Service

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
    private final AdminUserMapper adminUserMapper;
    private final LoginAttemptService loginAttemptService;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Autowired
    public AdminUserServiceImpl(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            SessionRegistry sessionRegistry,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            AdminUserMapper adminUserMapper,
            LoginAttemptService loginAttemptService,
            ObjectProvider<JavaMailSender> mailSenderProvider
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionRegistry = sessionRegistry;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.adminUserMapper = adminUserMapper;
        this.loginAttemptService = loginAttemptService;
        this.mailSenderProvider = mailSenderProvider;
    }

    public AdminUserServiceImpl(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            SessionRegistry sessionRegistry,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            AdminUserMapper adminUserMapper
    ) {
        this(userRepository, roleRepository, passwordEncoder, sessionRegistry, auditLogService, objectMapper, adminUserMapper, null, null);
    }


    @Override
    @Transactional(readOnly = true)
    public List<RoleOption> listAssignableRoles() {
        return roleRepository.findAll().stream()
                .filter(role -> ALLOWED_INTERNAL_ROLES.contains(role.getRoleName()))
                .map(adminUserMapper::toRoleOption)
                .toList();
    }

    /**
     * Retrieves paginated internal staff users filtered by optional role name, active status, and search keyword.
     * Eliminates N+1 queries by relying on UserRepository join fetch.
     *
     * @param roleName optional role filter name
     * @param active   optional active status filter
     * @param keyword  optional search term matching name, email, or phone
     * @param pageable pagination parameters
     * @return page of UserSummary DTOs
     */
    @Override
    @Transactional(readOnly = true)
    public Page<UserSummary> list(String roleName, Boolean active, String keyword, Pageable pageable) {
        String filterRole = (roleName != null && !roleName.isBlank()) ? roleName.trim() : null;
        String filterKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        Page<User> staffPage = userRepository.findInternalStaff(filterRole, active, filterKeyword, pageable);
        return staffPage.map(AdminUserMapper::toSummary);
    }

    /**
     * Provisions a new internal back-office staff account.
     * Validates uniqueness of email and phone, enforces internal role assignment whitelist,
     * hashes credentials with BCrypt, and appends a security audit entry.
     *
     * @param adminId   authenticated administrator user ID performing the operation
     * @param request   creation parameters DTO
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

        User user = adminUserMapper.toInternalStaffEntity(
                request,
                passwordEncoder.encode(request.password()),
                assignedRole
        );

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
     * @param adminId   authenticated administrator user ID
     * @param userId    target user ID to update
     * @param active    desired active state
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
                List<User> activeAdmins = userRepository.findActiveAdminsForUpdate();
                boolean targetIsActiveAdmin = activeAdmins.stream().anyMatch(u -> u.getId().equals(userId));
                if (targetIsActiveAdmin && activeAdmins.size() <= 1) {
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
     * @param adminId   authenticated administrator user ID
     * @param userId    target user ID to update
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
                List<User> activeAdmins = userRepository.findActiveAdminsForUpdate();
                boolean targetIsActiveAdmin = activeAdmins.stream().anyMatch(u -> u.getId().equals(userId));
                if (targetIsActiveAdmin && activeAdmins.size() <= 1) {
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
     * Resets the password of an internal back-office staff account in an emergency (UC28).
     * Enforces customer isolation guard: rejects any attempt to reset customer passwords.
     * Generates a secure random temporary password, hashes it, emails it to staff,
     * unlocks brute-force cooldown, invalidates existing sessions, and writes a clean audit log.
     *
     * @param adminId authenticated administrator performing the reset
     * @param targetUserId staff user whose password should be reset
     * @param ipAddress originating client IP
     */
    @Override
    @Transactional
    public void resetStaffPassword(Long adminId, Long targetUserId, String ipAddress) {
        if (targetUserId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA);
        }

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        Role targetRole = targetUser.getRole();
        if (targetRole == null || "ROLE_CUSTOMER".equalsIgnoreCase(targetRole.getRoleName())
                || !ALLOWED_INTERNAL_ROLES.contains(targetRole.getRoleName())) {
            throw new AppException(ErrorCode.CANNOT_RESET_CUSTOMER_PASSWORD);
        }

        String tempPassword = generateTemporaryPassword();
        targetUser.setPasswordHash(passwordEncoder.encode(tempPassword));
        userRepository.save(targetUser);

        // 1. Send temporary password directly to staff email
        if (mailSenderProvider != null) {
            JavaMailSender sender = mailSenderProvider.getIfAvailable();
            if (sender != null && targetUser.getEmail() != null && !targetUser.getEmail().isBlank()) {
                try {
                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setFrom("no-reply@aureliabook.local");
                    message.setTo(targetUser.getEmail());
                    message.setSubject(OtpMailContent.STAFF_PASSWORD_RESET_SUBJECT);
                    message.setText(OtpMailContent.staffPasswordResetBody(targetUser.getFullName(), tempPassword));
                    sender.send(message);
                } catch (Exception ex) {
                    log.error("Failed to send temporary password email to staff [{}]", targetUser.getEmail(), ex);
                }
            }
        }

        // 2. Clear brute-force cooldown if locked
        if (loginAttemptService != null) {
            loginAttemptService.resetAttempts(targetUserId);
        }

        // 3. Expire existing sessions
        expireUserSessions(targetUserId);

        // 4. Record audit entry (clean - no password or hash)
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", "RESET_STAFF_PASSWORD");
        details.put("actor_id", adminId);
        details.put("target_user_id", targetUserId);
        details.put("target_email", targetUser.getEmail());
        details.put("target_role", targetRole.getRoleName());

        auditLogService.record(
                adminId,
                "RESET_STAFF_PASSWORD",
                "users",
                targetUserId,
                toJson(details),
                ipAddress
        );

        log.info("Admin [{}] reset temporary password for staff [{}] ({}) from IP [{}]",
                adminId, targetUserId, targetUser.getEmail(), ipAddress);
    }

    /**
     * Generates a cryptographically strong random temporary password meeting system policy:
     * 12 chars containing uppercase, lowercase, digits, and special characters, <= 72 bytes.
     */
    private String generateTemporaryPassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghjkmnpqrstuvwxyz";
        String digits = "23456789";
        String special = "@#$%&*!";
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        sb.append(upper.charAt(rng.nextInt(upper.length())));
        sb.append(lower.charAt(rng.nextInt(lower.length())));
        sb.append(digits.charAt(rng.nextInt(digits.length())));
        sb.append(special.charAt(rng.nextInt(special.length())));
        String all = upper + lower + digits + special;
        for (int i = 0; i < 8; i++) {
            sb.append(all.charAt(rng.nextInt(all.length())));
        }
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
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
