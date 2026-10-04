package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.dto.response.UserSummary;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.impl.AdminUserServiceImpl;
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

import java.time.LocalDateTime;
import java.util.Collections;
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
        UserSummary summary = result.getContent().get(0);
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
    @DisplayName("create() should hash password, assign internal role, save user and write audit log")
    void testCreateStaff_Success() throws Exception {
        Long adminId = 1L;
        UserCreateRequest request = new UserCreateRequest(
                "manager@aureliabook.vn",
                "SecretP@ss123",
                "Tran Manager",
                "0912345678",
                2L
        );

        when(userRepository.existsByEmail("manager@aureliabook.vn")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(managerRole));
        when(passwordEncoder.encode("SecretP@ss123")).thenReturn("$2a$12$hashedPassword");

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

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(adminId), eq("USER_CREATE"), eq("users"), eq(100L), jsonCaptor.capture(), eq(clientIp));
        JsonNode root = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(root.get("action").asText()).isEqualTo("USER_CREATE");
        assertThat(root.get("created_user_id").asLong()).isEqualTo(100L);
        assertThat(root.get("email").asText()).isEqualTo("manager@aureliabook.vn");
        assertThat(root.get("role").asText()).isEqualTo("ROLE_MANAGER");
    }

    @Test
    @DisplayName("create() should canonicalize +84 phone number to 0xxxxxxxxx format")
    void testCreateStaff_CanonicalizesPlus84Phone() {
        Long adminId = 1L;
        UserCreateRequest request = new UserCreateRequest(
                "staff84@aureliabook.vn",
                "SecretP@ss123",
                "Staff 84",
                "+84912345678",
                2L
        );

        when(userRepository.existsByEmail("staff84@aureliabook.vn")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(managerRole));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        User savedUser = User.builder().id(101L).build();
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
                "duplicate@aureliabook.vn", "Pass123456@", "Name", "0912345678", 2L
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
                "new@aureliabook.vn", "Pass123456", "Name", "0912345678", 4L
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
    @DisplayName("setActive() should prevent deactivating the last active administrator")
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
        when(userRepository.countActiveAdmins()).thenReturn(1L);

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
    @DisplayName("changeRole() should prevent demoting the last active administrator")
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
        when(userRepository.countActiveAdmins()).thenReturn(1L);

        assertThatThrownBy(() -> adminUserService.changeRole(adminId, targetAdminId, 2L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_REVOKE_LAST_ADMIN);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("changeRole() should prevent self-demotion from ROLE_ADMIN")
    void testPreventSelfDemotion_ThrowsException() {
        Long adminId = 1L;

        User adminUser = User.builder()
                .id(adminId)
                .email("admin@aureliabook.vn")
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
        Long targetAdmin1Id = 1L;
        Long targetAdmin2Id = 2L;

        User targetAdmin1 = User.builder().id(targetAdmin1Id).email("admin1@aureliabook.vn").role(adminRole).isActive(true).build();
        User targetAdmin2 = User.builder().id(targetAdmin2Id).email("admin2@aureliabook.vn").role(adminRole).isActive(true).build();

        when(userRepository.findById(targetAdmin1Id)).thenReturn(Optional.of(targetAdmin1));
        when(userRepository.findById(targetAdmin2Id)).thenReturn(Optional.of(targetAdmin2));

        java.util.concurrent.atomic.AtomicInteger activeCount = new java.util.concurrent.atomic.AtomicInteger(2);
        when(userRepository.countActiveAdmins()).thenAnswer(inv -> (long) activeCount.get());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (!Boolean.TRUE.equals(u.getIsActive())) {
                activeCount.decrementAndGet();
            }
            return u;
        });

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);

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

        Object r1 = f1.get();
        Object r2 = f2.get();
        executor.shutdown();

        boolean oneSucceeded = Boolean.TRUE.equals(r1) || Boolean.TRUE.equals(r2);
        boolean oneFailedWithAppException = (r1 instanceof AppException ae1 && ae1.getErrorCode() == ErrorCode.CANNOT_REVOKE_LAST_ADMIN)
                || (r2 instanceof AppException ae2 && ae2.getErrorCode() == ErrorCode.CANNOT_REVOKE_LAST_ADMIN);

        assertThat(oneSucceeded).isTrue();
        assertThat(oneFailedWithAppException).isTrue();
        assertThat(activeCount.get()).isEqualTo(1);
    }
}
