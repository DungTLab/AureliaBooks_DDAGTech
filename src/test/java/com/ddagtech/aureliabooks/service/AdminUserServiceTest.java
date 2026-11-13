package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.mapper.AdminUserMapper;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.dto.response.UserSummary;
import com.ddagtech.aureliabooks.entity.AuditLog;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.AuditLogRepository;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.impl.AdminUserServiceImpl;
import com.ddagtech.aureliabooks.service.impl.AuditLogServiceImpl;
import com.ddagtech.aureliabooks.service.impl.OtpMailContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying business invariants and RBAC management in {@link AdminUserServiceImpl} (UC28).
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {
    @org.mockito.Spy
    private AdminUserMapper adminUserMapper = new AdminUserMapper();


    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SessionRegistry sessionRegistry;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private com.ddagtech.aureliabooks.security.LoginAttemptService loginAttemptService;

    @Mock
    private org.springframework.beans.factory.ObjectProvider<org.springframework.mail.javamail.JavaMailSender> mailSenderProvider;

    @Mock
    private org.springframework.mail.javamail.JavaMailSender mailSender;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AdminUserServiceImpl adminUserService;

    private Role adminRole;
    private Role managerRole;
    private Role staffRole;
    private Role customerRole;

    @BeforeEach
    void setUp() {
        adminRole = Role.builder().id(1L).roleName("ROLE_ADMIN").description("Administrator").build();
        managerRole = Role.builder().id(2L).roleName("ROLE_MANAGER").description("Store Manager").build();
        staffRole = Role.builder().id(3L).roleName("ROLE_SALE_STAFF").description("Sales Staff").build();
        customerRole = Role.builder().id(4L).roleName("ROLE_CUSTOMER").description("End Customer").build();
        lenient().when(roleRepository.findByRoleNameForUpdate("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        lenient().when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
    }

    @Test
    void assignableRolesExcludeCustomerAndUnknownRoles() {
        Role unknown = Role.builder().id(99L).roleName("ROLE_UNKNOWN").build();
        when(roleRepository.findAll()).thenReturn(List.of(adminRole, customerRole, managerRole, staffRole, unknown));
        assertThat(adminUserService.listAssignableRoles())
                .extracting(com.ddagtech.aureliabooks.dto.response.RoleOption::roleName)
                .containsExactly("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_SALE_STAFF");
    }

    @Test
    @DisplayName("list() should map internal staff entities to UserSummary DTOs")
    void testList_Success() {
        User staff = User.builder()
                .id(10L)
                .email("staff@aureliabook.vn")
                .fullName("Nguyen Staff")
                .phone("0987654321")
                .role(staffRole)
                .isActive(true)
                .build();

        Page<User> staffPage = new PageImpl<>(List.of(staff));
        when(userRepository.findInternalStaff(eq("ROLE_SALE_STAFF"), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(staffPage);

        Page<UserSummary> result = adminUserService.list("ROLE_SALE_STAFF", PageRequest.of(0, 10));

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        UserSummary summary = result.getContent().getFirst();
        assertThat(summary.id()).isEqualTo(10L);
        assertThat(summary.email()).isEqualTo("staff@aureliabook.vn");
        assertThat(summary.roleId()).isEqualTo(3L);
        assertThat(summary.roleName()).isEqualTo("ROLE_SALE_STAFF");
        assertThat(summary.active()).isTrue();
    }

    @Test
    @DisplayName("list() with active, keyword and role should pass filters to repository")
    void testList_WithFilters() {
        when(userRepository.findInternalStaff(eq("ROLE_MANAGER"), eq(true), eq("Nguyen"), any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<UserSummary> result = adminUserService.list("ROLE_MANAGER", true, "Nguyen", PageRequest.of(0, 20));

        assertThat(result).isNotNull();
        verify(userRepository).findInternalStaff(eq("ROLE_MANAGER"), eq(true), eq("Nguyen"), any(Pageable.class));
    }

    @Test
    @DisplayName("list() with ROLE_CUSTOMER should retrieve customer accounts")
    void testList_CustomerRole_Success() {
        User customer = User.builder()
                .id(99L)
                .email("customer@gmail.com")
                .fullName("Le Customer")
                .phone("0987654321")
                .role(customerRole)
                .isActive(true)
                .build();

        when(userRepository.findInternalStaff(eq("ROLE_CUSTOMER"), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(customer)));

        Page<UserSummary> result = adminUserService.list("ROLE_CUSTOMER", PageRequest.of(0, 20));

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        UserSummary summary = result.getContent().getFirst();
        assertThat(summary.id()).isEqualTo(99L);
        assertThat(summary.roleName()).isEqualTo("ROLE_CUSTOMER");
        assertThat(summary.email()).isEqualTo("customer@gmail.com");
    }

    @Test
    @DisplayName("setActive() on customer account should lock abusive customer and expire sessions")
    void testLockCustomerAccount_Success() {
        Long adminId = 1L;
        Long customerId = 99L;

        User customer = User.builder()
                .id(customerId)
                .email("bomhang@gmail.com")
                .role(customerRole)
                .isActive(true)
                .build();

        when(userRepository.findById(customerId)).thenReturn(Optional.of(customer));

        adminUserService.setActive(adminId, customerId, false, "10.0.0.1");

        assertThat(customer.getIsActive()).isFalse();
        verify(userRepository).save(customer);
        verify(auditLogService).record(eq(adminId), eq("USER_STATUS_TOGGLE"), eq("users"), eq(customerId), anyString(), eq("10.0.0.1"));
    }

    @Test
    @DisplayName("create() should auto-generate initial password, hash it, save user, send email, and write audit log")
    void testCreateStaff_Success() throws Exception {
        Long adminId = 1L;
        UserCreateRequest request = new UserCreateRequest(
                "manager@aureliabook.vn",
                "Tran Manager",
                "0912345678",
                2L
        );

        org.springframework.mail.javamail.JavaMailSender mailSender = mock(org.springframework.mail.javamail.JavaMailSender.class);
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);

        when(userRepository.existsByEmail("manager@aureliabook.vn")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(managerRole));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedPassword");

        User savedUser = User.builder()
                .id(100L)
                .email("manager@aureliabook.vn")
                .passwordHash("$2a$12$hashedPassword")
                .fullName("Tran Manager")
                .phone("0912345678")
                .role(managerRole)
                .authProvider(User.AuthProvider.LOCAL)
                .isActive(true)
                .build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        String clientIp = "192.168.1.100";
        Long createdId = adminUserService.create(adminId, request, clientIp);

        assertThat(createdId).isEqualTo(100L);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User captured = userCaptor.getValue();
        assertThat(captured.getEmail()).isEqualTo("manager@aureliabook.vn");
        assertThat(captured.getPasswordHash()).isEqualTo("$2a$12$hashedPassword");
        assertThat(captured.getAuthProvider()).isEqualTo(User.AuthProvider.LOCAL);
        assertThat(captured.getIsActive()).isTrue();
        assertThat(captured.getRole().getRoleName()).isEqualTo("ROLE_MANAGER");
        assertThat(captured.isEmailVerified()).isFalse();
        assertThat(captured.isPhoneVerified()).isFalse();

        ArgumentCaptor<org.springframework.mail.SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        verify(mailSender).send(mailCaptor.capture());
        org.springframework.mail.SimpleMailMessage sentMail = mailCaptor.getValue();
        assertThat(sentMail.getTo()).containsExactly("manager@aureliabook.vn");
        assertThat(sentMail.getSubject()).isEqualTo(OtpMailContent.STAFF_ACCOUNT_CREATED_SUBJECT);
        assertThat(sentMail.getText()).contains("/internal/account/email").contains("/internal/forgot-password");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(adminId), eq("USER_CREATE"), eq("users"), eq(100L), jsonCaptor.capture(), eq(clientIp));
        JsonNode root = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(root.get("action").asText()).isEqualTo("USER_CREATE");
        assertThat(root.get("created_user_id").asLong()).isEqualTo(100L);
        assertThat(root.get("email").asText()).isEqualTo("manager@aureliabook.vn");
        assertThat(root.get("role").asText()).isEqualTo("ROLE_MANAGER");
        assertThat(root.get("email_verified").asBoolean()).isFalse();
        assertThat(root.get("phone_verified").asBoolean()).isFalse();
        assertThat(jsonCaptor.getValue().toLowerCase()).doesNotContain("password", "hash", "token");
    }

    @Test
    @DisplayName("create() should canonicalize +84 phone number to 0xxxxxxxxx format")
    void testCreateStaff_CanonicalizesPlus84Phone() {
        Long adminId = 1L;
        UserCreateRequest request = new UserCreateRequest(
                "staff84@aureliabook.vn",
                "Staff 84",
                "+84912345678",
                2L
        );

        when(userRepository.existsByEmail("staff84@aureliabook.vn")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(managerRole));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        User savedUser = User.builder().id(101L).email(request.email()).fullName(request.fullName()).role(managerRole).build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        adminUserService.create(adminId, request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPhone()).isEqualTo("0912345678");
    }

    @Test
    @DisplayName("create() should throw USER_EXISTED when email already exists")
    void testCreateStaff_DuplicateEmail_ThrowsException() {
        UserCreateRequest request = new UserCreateRequest(
                "duplicate@aureliabook.vn", "Name", "0912345678", 2L
        );
        when(userRepository.existsByEmail("duplicate@aureliabook.vn")).thenReturn(true);

        assertThatThrownBy(() -> adminUserService.create(1L, request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_EXISTED);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("create() should throw INVALID_ROLE_ASSIGNMENT when attempting to assign ROLE_CUSTOMER")
    void testCreateStaff_CustomerRole_ThrowsInvalidRoleAssignmentException() {
        UserCreateRequest request = new UserCreateRequest(
                "new@aureliabook.vn", "Name", "0912345678", 4L
        );
        when(userRepository.existsByEmail("new@aureliabook.vn")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(roleRepository.findById(4L)).thenReturn(Optional.of(customerRole));

        assertThatThrownBy(() -> adminUserService.create(1L, request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ROLE_ASSIGNMENT);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("create() should auto-generate initial password meeting length, complexity and <= 72 bytes")
    void testCreateStaff_AutoGeneratesSecurePassword_MeetingPolicy() {
        Long adminId = 1L;
        UserCreateRequest request = new UserCreateRequest(
                "newstaff@aureliabook.vn", "Staff Name", "0912345678", 2L
        );
        when(userRepository.existsByEmail("newstaff@aureliabook.vn")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(managerRole));

        ArgumentCaptor<String> rawPasswordCaptor = ArgumentCaptor.forClass(String.class);
        when(passwordEncoder.encode(rawPasswordCaptor.capture())).thenReturn("$2a$12$hashedPassword");

        User savedUser = User.builder().id(102L).email(request.email()).fullName(request.fullName()).role(managerRole).build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        adminUserService.create(adminId, request);

        String generatedPassword = rawPasswordCaptor.getValue();
        assertThat(generatedPassword).isNotBlank();
        assertThat(generatedPassword.length()).isEqualTo(12);
        assertThat(generatedPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(72);
        assertThat(generatedPassword).matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#$%&*!]).{12}$");
    }

    @Test
    @DisplayName("setActive() should prevent self-lock and throw CANNOT_LOCK_SELF (BR-08-01)")
    void testPreventSelfLock_ThrowsCannotLockSelfException() {
        Long adminId = 5L;
        Long targetUserId = 5L;

        assertThatThrownBy(() -> adminUserService.setActive(adminId, targetUserId, false))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_LOCK_SELF);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("setActive() should prevent deactivating the last active administrator (UC28-R05)")
    void testPreventRevokingLastAdmin_Deactivation_ThrowsException() {
        Long adminId = 1L;
        Long targetAdminId = 2L;

        User targetAdmin = User.builder()
                .id(targetAdminId)
                .email("otheradmin@aureliabook.vn")
                .role(adminRole)
                .isActive(true)
                .build();

        when(userRepository.findById(targetAdminId)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.findActiveAdminsForUpdate()).thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> adminUserService.setActive(adminId, targetAdminId, false))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_REVOKE_LAST_ADMIN);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("setActive() with active=false should expire active sessions for target user")
    void testLockUser_TriggersSessionExpiration() throws Exception {
        Long adminId = 1L;
        Long targetUserId = 20L;

        User targetUser = User.builder()
                .id(targetUserId)
                .email("staff@aureliabook.vn")
                .role(staffRole)
                .isActive(true)
                .build();

        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));

        CustomUserDetails userDetails = new CustomUserDetails(targetUser);
        SessionInformation mockSession = new SessionInformation(userDetails, "session-abc-123", new Date());

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(userDetails));
        when(sessionRegistry.getAllSessions(userDetails, false)).thenReturn(List.of(mockSession));

        String clientIp = "10.0.0.1";
        adminUserService.setActive(adminId, targetUserId, false, clientIp);

        assertThat(targetUser.getIsActive()).isFalse();
        assertThat(mockSession.isExpired()).isTrue();
        verify(userRepository).save(targetUser);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(adminId), eq("USER_STATUS_TOGGLE"), eq("users"), eq(targetUserId), jsonCaptor.capture(), eq(clientIp));
        JsonNode root = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(root.get("action").asText()).isEqualTo("USER_STATUS_TOGGLE");
        assertThat(root.get("before").get("active").asBoolean()).isTrue();
        assertThat(root.get("after").get("active").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("changeRole() should prevent demoting the last active administrator (UC28-R05)")
    void testPreventRevokingLastAdmin_RoleDemotion_ThrowsException() {
        Long adminId = 1L;
        Long targetAdminId = 2L;

        User targetAdmin = User.builder()
                .id(targetAdminId)
                .email("admin2@aureliabook.vn")
                .role(adminRole)
                .isActive(true)
                .build();

        when(userRepository.findById(targetAdminId)).thenReturn(Optional.of(targetAdmin));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(managerRole));
        when(userRepository.findActiveAdminsForUpdate()).thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> adminUserService.changeRole(adminId, targetAdminId, 2L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_REVOKE_LAST_ADMIN);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("changeRole() should prevent self-demotion from ROLE_ADMIN")
    void testPreventSelfDemotion_ThrowsException() {
        Long adminId = 2L;

        User adminUser = User.builder()
                .id(adminId)
                .email("admin2@aureliabook.vn")
                .role(adminRole)
                .isActive(true)
                .build();

        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(roleRepository.findById(3L)).thenReturn(Optional.of(staffRole));

        assertThatThrownBy(() -> adminUserService.changeRole(adminId, adminId, 3L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_REVOKE_LAST_ADMIN);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("setActive() should allow deactivating a non-admin account even if ID is 1 (reviewer probe case)")
    void testDeactivateUser_UserId1_StaffRole_Success() {
        Long adminId = 2L;
        Long targetUserId = 1L;

        User staffUser = User.builder()
                .id(targetUserId)
                .email("staff1@aureliabook.vn")
                .role(staffRole)
                .isActive(true)
                .build();

        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(staffUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        adminUserService.setActive(adminId, targetUserId, false, "127.0.0.1");

        assertThat(staffUser.getIsActive()).isFalse();
        verify(userRepository).save(staffUser);
    }

    @Test
    @DisplayName("changeRole() should allow changing role of user ID 1 when other active admins exist")
    void testChangeRole_UserId1_AdminRole_WhenOtherAdminsExist_Success() {
        Long otherAdminId = 2L;
        Long targetAdminId = 1L;

        User targetAdmin = User.builder()
                .id(targetAdminId)
                .email("admin1@aureliabook.vn")
                .role(adminRole)
                .isActive(true)
                .build();

        User otherAdmin = User.builder()
                .id(otherAdminId)
                .email("admin2@aureliabook.vn")
                .role(adminRole)
                .isActive(true)
                .build();

        when(userRepository.findById(targetAdminId)).thenReturn(Optional.of(targetAdmin));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(managerRole));
        when(userRepository.findActiveAdminsForUpdate()).thenReturn(List.of(targetAdmin, otherAdmin));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        adminUserService.changeRole(otherAdminId, targetAdminId, 2L, "127.0.0.1");

        assertThat(targetAdmin.getRole()).isEqualTo(managerRole);
        verify(userRepository).save(targetAdmin);
    }

    @Test
    @DisplayName("setActive() should prevent deactivating the last active administrator regardless of user ID")
    void testPreventDeactivateLastAdmin_ThrowsException() {
        Long callerAdminId = 2L;
        Long targetAdminId = 1L;

        User targetAdmin = User.builder()
                .id(targetAdminId)
                .email("admin1@aureliabook.vn")
                .role(adminRole)
                .isActive(true)
                .build();

        when(userRepository.findById(targetAdminId)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.findActiveAdminsForUpdate()).thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> adminUserService.setActive(callerAdminId, targetAdminId, false, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_REVOKE_LAST_ADMIN);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("changeRole() should update role and expire existing user sessions")
    void testChangeRole_Success_TriggersSessionExpiration() throws Exception {
        Long adminId = 1L;
        Long targetUserId = 30L;

        User staffUser = User.builder()
                .id(targetUserId)
                .email("staff@aureliabook.vn")
                .role(staffRole)
                .isActive(true)
                .build();

        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(staffUser));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(managerRole));

        CustomUserDetails userDetails = new CustomUserDetails(staffUser);
        SessionInformation mockSession = new SessionInformation(userDetails, "session-xyz-789", new Date());

        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(userDetails));
        when(sessionRegistry.getAllSessions(userDetails, false)).thenReturn(List.of(mockSession));

        String clientIp = "10.0.0.2";
        adminUserService.changeRole(adminId, targetUserId, 2L, clientIp);

        assertThat(staffUser.getRole()).isEqualTo(managerRole);
        assertThat(mockSession.isExpired()).isTrue();
        verify(userRepository).save(staffUser);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(adminId), eq("USER_ROLE_UPDATE"), eq("users"), eq(targetUserId), jsonCaptor.capture(), eq(clientIp));
        JsonNode root = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(root.get("action").asText()).isEqualTo("USER_ROLE_UPDATE");
        assertThat(root.get("before").get("role").asText()).isEqualTo("ROLE_SALE_STAFF");
        assertThat(root.get("after").get("role").asText()).isEqualTo("ROLE_MANAGER");
    }

    @Test
    @DisplayName("setActive() concurrency test: simultaneous deactivation of two admins allows only one to succeed")
    void testConcurrentAdminDeactivation_PreventsZeroAdmins() throws Exception {
        Long adminId = 999L;
        Long targetAdmin1Id = 2L;
        Long targetAdmin2Id = 3L;

        User targetAdmin1 = User.builder().id(targetAdmin1Id).email("admin2@aureliabook.vn").role(adminRole).isActive(true).build();
        User targetAdmin2 = User.builder().id(targetAdmin2Id).email("admin3@aureliabook.vn").role(adminRole).isActive(true).build();

        when(userRepository.findById(targetAdmin1Id)).thenReturn(Optional.of(targetAdmin1));
        when(userRepository.findById(targetAdmin2Id)).thenReturn(Optional.of(targetAdmin2));

        java.util.Set<Long> activeAdminIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        activeAdminIds.add(targetAdmin1Id);
        activeAdminIds.add(targetAdmin2Id);
        when(userRepository.findActiveAdminsForUpdate()).thenAnswer(inv -> {
            List<User> list = new java.util.ArrayList<>();
            if (activeAdminIds.contains(targetAdmin1Id)) list.add(targetAdmin1);
            if (activeAdminIds.contains(targetAdmin2Id)) list.add(targetAdmin2);
            return list;
        });
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (!Boolean.TRUE.equals(u.getIsActive())) {
                activeAdminIds.remove(u.getId());
            }
            return u;
        });

        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        Object r1;
        Object r2;

        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<?> f1 = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    adminUserService.setActive(adminId, targetAdmin1Id, false);
                    return true;
                } catch (Exception e) {
                    return e;
                }
            });

            java.util.concurrent.Future<?> f2 = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    adminUserService.setActive(adminId, targetAdmin2Id, false);
                    return true;
                } catch (Exception e) {
                    return e;
                }
            });

            readyLatch.await();
            startLatch.countDown();

            r1 = f1.get();
            r2 = f2.get();
        }

        boolean oneSucceeded = Boolean.TRUE.equals(r1) || Boolean.TRUE.equals(r2);
        boolean oneFailedWithAppException = (r1 instanceof AppException ae1 && ae1.getErrorCode() == ErrorCode.CANNOT_REVOKE_LAST_ADMIN)
                || (r2 instanceof AppException ae2 && ae2.getErrorCode() == ErrorCode.CANNOT_REVOKE_LAST_ADMIN);

        assertThat(oneSucceeded).isTrue();
        assertThat(oneFailedWithAppException).isTrue();
        assertThat(activeAdminIds).hasSize(1);
    }

    @Test
    @DisplayName("setActive() should defer lock release until transaction afterCompletion is triggered")
    void testPreventLastAdmin_AcrossSpringTransactionCommitBoundary() {
        Long adminId = 999L;
        Long targetAdminId = 2L;

        User admin1 = User.builder()
                .id(1L)
                .email("admin1@aureliabook.vn")
                .role(adminRole)
                .isActive(true)
                .build();

        User targetAdmin = User.builder()
                .id(targetAdminId)
                .email("admin2@aureliabook.vn")
                .role(adminRole)
                .isActive(true)
                .build();

        when(userRepository.findById(targetAdminId)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.findActiveAdminsForUpdate()).thenReturn(List.of(admin1, targetAdmin));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            adminUserService.setActive(adminId, targetAdminId, false);
            List<org.springframework.transaction.support.TransactionSynchronization> synchronizations =
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).isNotEmpty();

            for (org.springframework.transaction.support.TransactionSynchronization sync : synchronizations) {
                sync.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);
            }
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        verify(roleRepository).findByRoleNameForUpdate("ROLE_ADMIN");
        verify(userRepository).save(targetAdmin);
    }

    @Test
    @DisplayName("setActive() locking read findActiveAdminsForUpdate() should strictly prevent last admin deactivation (UC28-R05)")
    void testPreventRevokingLastAdmin_LockingReadBypassesStaleSnapshot() {
        Long adminId = 999L;
        Long targetAdminId = 2L;

        User targetAdmin = User.builder()
                .id(targetAdminId)
                .email("admin2@aureliabook.vn")
                .role(adminRole)
                .isActive(true)
                .build();

        when(userRepository.findById(targetAdminId)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.findActiveAdminsForUpdate()).thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> adminUserService.setActive(adminId, targetAdminId, false))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_REVOKE_LAST_ADMIN);

        verify(roleRepository).findByRoleNameForUpdate("ROLE_ADMIN");
        verify(userRepository).findActiveAdminsForUpdate();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("setActive() with real AuditLogServiceImpl should record audit entry without lock contention (UC28-R11)")
    void testSetActive_WithRealAuditLogService_Success() {
        AuditLogRepository mockAuditLogRepo = mock(AuditLogRepository.class);
        AuditLogService realAuditLogService = new AuditLogServiceImpl(mockAuditLogRepo, userRepository);

        AdminUserServiceImpl serviceWithRealAudit = new AdminUserServiceImpl(
                userRepository,
                roleRepository,
                passwordEncoder,
                sessionRegistry,
                realAuditLogService,
                objectMapper
        , new AdminUserMapper());

        Long actorAdminId = 1L;
        Long targetAdminId = 2L;

        User actorAdmin = User.builder().id(actorAdminId).email("admin1@aureliabook.vn").role(adminRole).isActive(true).build();
        User targetAdmin = User.builder().id(targetAdminId).email("admin2@aureliabook.vn").role(adminRole).isActive(true).build();

        when(userRepository.findById(targetAdminId)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.findById(actorAdminId)).thenReturn(Optional.of(actorAdmin));
        when(userRepository.findActiveAdminsForUpdate()).thenReturn(List.of(actorAdmin, targetAdmin));

        serviceWithRealAudit.setActive(actorAdminId, targetAdminId, false, "127.0.0.1");

        assertThat(targetAdmin.getIsActive()).isFalse();
        verify(userRepository).save(targetAdmin);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mockAuditLogRepo).save(captor.capture());
        AuditLog savedAudit = captor.getValue();
        assertThat(savedAudit.getUser()).isEqualTo(actorAdmin);
        assertThat(savedAudit.getAction()).isEqualTo("USER_STATUS_TOGGLE");
        assertThat(savedAudit.getTargetId()).isEqualTo(targetAdminId);
        assertThat(savedAudit.getTargetTable()).isEqualTo("users");
        assertThat(savedAudit.getIpAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("changeRole() with real AuditLogServiceImpl should record audit entry without lock contention (UC28-R11)")
    void testChangeRole_WithRealAuditLogService_Success() {
        AuditLogRepository mockAuditLogRepo = mock(AuditLogRepository.class);
        AuditLogService realAuditLogService = new AuditLogServiceImpl(mockAuditLogRepo, userRepository);

        AdminUserServiceImpl serviceWithRealAudit = new AdminUserServiceImpl(
                userRepository,
                roleRepository,
                passwordEncoder,
                sessionRegistry,
                realAuditLogService,
                objectMapper
        , new AdminUserMapper());

        Long actorAdminId = 1L;
        Long targetAdminId = 2L;

        User actorAdmin = User.builder().id(actorAdminId).email("admin1@aureliabook.vn").role(adminRole).isActive(true).build();
        User targetAdmin = User.builder().id(targetAdminId).email("admin2@aureliabook.vn").role(adminRole).isActive(true).build();

        when(userRepository.findById(targetAdminId)).thenReturn(Optional.of(targetAdmin));
        when(userRepository.findById(actorAdminId)).thenReturn(Optional.of(actorAdmin));
        when(roleRepository.findById(3L)).thenReturn(Optional.of(staffRole));
        when(userRepository.findActiveAdminsForUpdate()).thenReturn(List.of(actorAdmin, targetAdmin));

        serviceWithRealAudit.changeRole(actorAdminId, targetAdminId, 3L, "127.0.0.1");

        assertThat(targetAdmin.getRole()).isEqualTo(staffRole);
        verify(userRepository).save(targetAdmin);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mockAuditLogRepo).save(captor.capture());
        AuditLog savedAudit = captor.getValue();
        assertThat(savedAudit.getUser()).isEqualTo(actorAdmin);
        assertThat(savedAudit.getAction()).isEqualTo("USER_ROLE_UPDATE");
        assertThat(savedAudit.getTargetId()).isEqualTo(targetAdminId);
        assertThat(savedAudit.getTargetTable()).isEqualTo("users");
    }

    @Test
    @DisplayName("create() should use custom configured mailFrom address when specified")
    void testCreateStaff_UsesConfiguredMailFrom() {
        String customFrom = "custom-auth@aureliabook.vn";
        AdminUserServiceImpl customMailService = new AdminUserServiceImpl(
                userRepository,
                roleRepository,
                passwordEncoder,
                sessionRegistry,
                auditLogService,
                objectMapper,
                adminUserMapper,
                loginAttemptService,
                mailSenderProvider,
                customFrom
        );

        Long adminId = 1L;
        com.ddagtech.aureliabooks.dto.request.UserCreateRequest request =
                new com.ddagtech.aureliabooks.dto.request.UserCreateRequest(
                        "custom.staff@aureliabook.vn",
                        "Staff Custom",
                        "0912345678",
                        3L
                );

        when(userRepository.existsByEmail("custom.staff@aureliabook.vn")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(roleRepository.findById(3L)).thenReturn(Optional.of(staffRole));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(109L);
            return u;
        });

        customMailService.create(adminId, request, "192.168.1.100");

        ArgumentCaptor<org.springframework.mail.SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        verify(mailSender).send(mailCaptor.capture());
        assertThat(mailCaptor.getValue().getFrom()).isEqualTo(customFrom);
    }

    @Test
    @DisplayName("create() should throw EMAIL_DELIVERY_FAILED and not record audit when mail dispatch fails")
    void testCreateStaff_MailFailure_ThrowsExceptionAndRollsBack() {
        Long adminId = 1L;
        UserCreateRequest request = new UserCreateRequest(
                "manager_fail@aureliabook.vn",
                "Tran Fail",
                "0912345678",
                2L
        );

        when(userRepository.existsByEmail("manager_fail@aureliabook.vn")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(managerRole));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$12$hashedPassword");

        User savedUser = User.builder()
                .id(105L)
                .email("manager_fail@aureliabook.vn")
                .fullName("Tran Fail")
                .role(managerRole)
                .build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        doThrow(new org.springframework.mail.MailSendException("SMTP connection refused"))
                .when(mailSender).send(any(org.springframework.mail.SimpleMailMessage.class));

        assertThatThrownBy(() -> adminUserService.create(adminId, request, "192.168.1.100"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_DELIVERY_FAILED);

        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("updateStaffContact() updates phone and unverified email without mutating passwordHash, invalidates sessions on email change, and audits cleanly")
    void testUpdateStaffContact_Success_ResetsVerifiedFlagsAndPreservesPassword() {
        Long adminId = 1L;
        Long targetId = 20L;
        User staff = User.builder()
                .id(targetId)
                .email("old.staff@aureliabook.vn")
                .phone("0901111111")
                .passwordHash("$2a$12$untouchedSecret")
                .role(staffRole)
                .isActive(true)
                .emailVerified(true)
                .phoneVerified(true)
                .build();

        com.ddagtech.aureliabooks.security.CustomUserDetails principal = new com.ddagtech.aureliabooks.security.CustomUserDetails(staff);
        org.springframework.security.core.session.SessionInformation activeSession = org.mockito.Mockito.mock(org.springframework.security.core.session.SessionInformation.class);

        when(userRepository.findOwnerForUpdate(targetId)).thenReturn(Optional.of(staff));
        when(userRepository.existsByEmail("new.staff@aureliabook.vn")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(principal));
        when(sessionRegistry.getAllSessions(principal, false)).thenReturn(List.of(activeSession));

        var request = new com.ddagtech.aureliabooks.dto.request.StaffContactUpdateRequest(
                " New.Staff@AureliaBook.VN ",
                "+84912345678",
                true,
                "Lost previous inbox"
        );

        adminUserService.updateStaffContact(adminId, targetId, request, "10.0.0.8");

        assertThat(staff.getEmail()).isEqualTo("new.staff@aureliabook.vn");
        assertThat(staff.getPhone()).isEqualTo("0912345678");
        assertThat(staff.isEmailVerified()).isFalse();
        assertThat(staff.isPhoneVerified()).isFalse();
        assertThat(staff.getPasswordHash()).isEqualTo("$2a$12$untouchedSecret");
        verify(activeSession).expireNow();
        verify(passwordEncoder, never()).encode(anyString());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(adminId), eq("ADMIN_STAFF_CONTACT_UPDATED"), eq("users"), eq(targetId), jsonCaptor.capture(), eq("10.0.0.8"));
        assertThat(jsonCaptor.getValue()).contains("\"identity_confirmed\":true", "\"email_verified\":false", "\"phone_verified\":false");
        assertThat(jsonCaptor.getValue().toLowerCase()).doesNotContain("password", "otp", "token", "hash");
    }

    @Test
    @DisplayName("updateStaffContact() rejects self-edit, CUSTOMER target, inactive staff, missing identity confirmation, and duplicate email/phone")
    void testUpdateStaffContact_EnforcesAllGuards() {
        var req = new com.ddagtech.aureliabooks.dto.request.StaffContactUpdateRequest("a@aureliabook.vn", "0912345678", true, null);

        assertThatThrownBy(() -> adminUserService.updateStaffContact(1L, 1L, req, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        User customer = User.builder().id(50L).role(customerRole).isActive(true).email("c@example.com").build();
        when(userRepository.findOwnerForUpdate(50L)).thenReturn(Optional.of(customer));
        assertThatThrownBy(() -> adminUserService.updateStaffContact(1L, 50L, req, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        User lockedStaff = User.builder().id(21L).role(staffRole).isActive(false).email("s21@aureliabook.vn").build();
        when(userRepository.findOwnerForUpdate(21L)).thenReturn(Optional.of(lockedStaff));
        assertThatThrownBy(() -> adminUserService.updateStaffContact(1L, 21L, req, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED);

        User activeStaff = User.builder().id(22L).role(staffRole).isActive(true).email("s22@aureliabook.vn").build();
        when(userRepository.findOwnerForUpdate(22L)).thenReturn(Optional.of(activeStaff));
        var unconfirmed = new com.ddagtech.aureliabooks.dto.request.StaffContactUpdateRequest("x@aureliabook.vn", null, false, null);
        assertThatThrownBy(() -> adminUserService.updateStaffContact(1L, 22L, unconfirmed, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

        when(userRepository.existsByEmail("dup@aureliabook.vn")).thenReturn(true);
        var duplicate = new com.ddagtech.aureliabooks.dto.request.StaffContactUpdateRequest("dup@aureliabook.vn", null, true, null);
        assertThatThrownBy(() -> adminUserService.updateStaffContact(1L, 22L, duplicate, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_EXISTED);
    }

    @Test
    @DisplayName("revokeEmailVerification() resets emailVerified to false for internal staff and writes clean audit log")
    void testRevokeEmailVerification_SuccessAndGuards() {
        User staff = User.builder().id(20L).role(staffRole).isActive(true).email("s@aureliabook.vn").emailVerified(true).build();
        when(userRepository.findOwnerForUpdate(20L)).thenReturn(Optional.of(staff));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        adminUserService.revokeEmailVerification(1L, 20L, "127.0.0.1");

        assertThat(staff.isEmailVerified()).isFalse();
        verify(auditLogService).record(eq(1L), eq("ADMIN_STAFF_EMAIL_REVOKED"), eq("users"), eq(20L), contains("\"email_verified\":false"), eq("127.0.0.1"));

        assertThatThrownBy(() -> adminUserService.revokeEmailVerification(1L, 1L, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("updateStaffContact() with phone-only change never emits email_verified:true proof in audit details")
    void testUpdateStaffContact_PhoneOnlyUpdate_DoesNotAuditEmailVerifiedTrue() {
        Long adminId = 1L;
        Long targetId = 20L;
        User staff = User.builder()
                .id(targetId)
                .email("legacy.staff@aureliabook.vn")
                .phone("0901111111")
                .passwordHash("$2a$12$untouchedSecret")
                .role(staffRole)
                .isActive(true)
                .emailVerified(true)
                .phoneVerified(false)
                .build();

        when(userRepository.findOwnerForUpdate(targetId)).thenReturn(Optional.of(staff));
        when(userRepository.existsByPhone("0988776655")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var phoneOnlyRequest = new com.ddagtech.aureliabooks.dto.request.StaffContactUpdateRequest(
                null,
                "0988776655",
                true,
                "Staff updated mobile number"
        );

        adminUserService.updateStaffContact(adminId, targetId, phoneOnlyRequest, "10.0.0.9");

        assertThat(staff.getPhone()).isEqualTo("0988776655");
        assertThat(staff.isPhoneVerified()).isFalse();
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(adminId), eq("ADMIN_STAFF_CONTACT_UPDATED"), eq("users"), eq(targetId), jsonCaptor.capture(), eq("10.0.0.9"));
        assertThat(jsonCaptor.getValue())
                .contains("\"email_changed\":false", "\"email_verification_reset\":false", "\"phone_changed\":true", "\"phone_verified\":false")
                .doesNotContain("\"email_verified\":true");
    }
}
