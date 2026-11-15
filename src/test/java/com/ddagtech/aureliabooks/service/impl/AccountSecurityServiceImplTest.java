package com.ddagtech.aureliabooks.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.PasswordChangeRequest;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.service.AuditLogService;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class AccountSecurityServiceImplTest {

    @Mock
    private UserRepository users;

    @Mock
    private RoleRepository roles;

    @Mock
    private PasswordEncoder passwords;

    @Mock
    private LoginAttemptService attempts;

    @Mock
    private SessionRegistry sessions;

    @Mock
    private AuditLogService audit;

    private AccountSecurityServiceImpl service;

    private Role staffRole;
    private Role customerRole;

    @BeforeEach
    void setUp() {
        service = new AccountSecurityServiceImpl(users, roles, passwords, attempts, sessions, audit);
        staffRole = Role.builder().id(3L).roleName("ROLE_SALE_STAFF").build();
        customerRole = Role.builder().id(4L).roleName("ROLE_CUSTOMER").build();
    }

    @Test
    void resetRechecksLockedAccountBeforeChangingPassword() {
        User account = User.builder().id(7L).role(staffRole)
                .email("customer@example.test").emailVerified(true).passwordHash("current-hash").build();
        when(users.findOwnerForUpdate(7L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.reset(7L, "current-hash", "EMAIL", account.getEmail(), "new-hash", "ip"))
                .isInstanceOf(IllegalArgumentException.class);
        account.setRole(customerRole);
        account.setIsActive(false);
        assertThatThrownBy(() -> service.reset(7L, "current-hash", "EMAIL", account.getEmail(), "new-hash", "ip"))
                .isInstanceOf(IllegalArgumentException.class);
        account.setIsActive(true);
        assertThatThrownBy(() -> service.reset(7L, "stale-hash", "EMAIL", account.getEmail(), "new-hash", "ip"))
                .isInstanceOf(IllegalArgumentException.class);
        account.setEmailVerified(false);
        assertThatThrownBy(() -> service.reset(7L, "current-hash", "EMAIL", account.getEmail(), "new-hash", "ip"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(account.getPasswordHash()).isEqualTo("current-hash");
        verify(users, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(audit, sessions, attempts);
    }

    @Test
    void internalResetRechecksCurrentRoleEmailAndHashUnderLock() {
        User staff = User.builder().id(7L).role(staffRole).email("staff@example.test")
            .emailVerified(true).passwordHash("current-hash").build();
        when(users.findOwnerForUpdate(7L)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> service.resetStaff(7L, "current-hash", "previous@example.test", "new-hash", "ip"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resetStaff(7L, "stale-hash", staff.getEmail(), "new-hash", "ip"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resetStaff(7L, "current-hash", staff.getEmail(), "new-hash", "ip"))
            .isInstanceOf(IllegalArgumentException.class);
        staff.setRole(customerRole);
        assertThatThrownBy(() -> service.resetStaff(7L, "current-hash", staff.getEmail(), "new-hash", "ip"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(staff.getPasswordHash()).isEqualTo("current-hash");
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(audit, sessions, attempts);
    }

    @Test
    void internalResetExpiresSessionsOnlyAfterCommit() {
        User staff = User.builder().id(7L).role(staffRole).email("staff@example.test")
            .emailVerified(true).passwordHash("current-hash").build();
        var principal = new CustomUserDetails(staff);
        var session = new SessionInformation(principal, "staff-session", new Date());
        when(users.findOwnerForUpdate(7L)).thenReturn(Optional.of(staff));
        when(users.saveAndFlush(staff)).thenReturn(staff);
        when(users.hasCurrentStaffEmailProof(staff)).thenReturn(true);
        when(sessions.getAllPrincipals()).thenReturn(List.of(principal));
        when(sessions.getAllSessions(principal, false)).thenReturn(List.of(session));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.resetStaff(7L, "current-hash", staff.getEmail(), "new-hash", "ip");
            assertThat(staff.getPasswordHash()).isEqualTo("new-hash");
            assertThat(session.isExpired()).isFalse();
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            assertThat(session.isExpired()).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(audit).record(7L, "STAFF_PASSWORD_RESET", "users", 7L, "{}", "ip");
    }

    @Test
    @DisplayName("changeStaffPassword should update password and record audit log for staff")
    void changeStaffPassword_Success() {
        Long staffId = 15L;
        User staff = User.builder()
                .id(staffId)
                .email("staff@aureliabook.vn")
                .passwordHash("$2a$12$oldHashedPassword")
                .role(staffRole)
                .isActive(true)
                .build();

        when(attempts.authenticate(eq(staffId), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(users.findPasswordHash(staffId)).thenReturn(Optional.of(staff.getPasswordHash()));
        when(users.findOwnerForUpdate(staffId)).thenReturn(Optional.of(staff));
        when(passwords.matches("OldPassword123@", "$2a$12$oldHashedPassword")).thenReturn(true);
        when(passwords.matches("NewStrongP@ss123", "$2a$12$oldHashedPassword")).thenReturn(false);
        when(passwords.encode("NewStrongP@ss123")).thenReturn("$2a$12$newHashedPassword");
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        PasswordChangeRequest request = new PasswordChangeRequest("OldPassword123@", "NewStrongP@ss123", "NewStrongP@ss123");
        User result = service.changeStaffPassword(staffId, request, "session-1", "127.0.0.1");

        assertThat(result.getPasswordHash()).isEqualTo("$2a$12$newHashedPassword");
        verify(users).saveAndFlush(staff);
        verify(audit).record(eq(staffId), eq("STAFF_PASSWORD_CHANGED"), eq("users"), eq(staffId), contains("ROLE_SALE_STAFF"), eq("127.0.0.1"));
    }

    @Test
    @DisplayName("changeStaffPassword should reject customer role")
    void changeStaffPassword_RejectsCustomerRole() {
        Long customerId = 99L;
        User customer = User.builder()
                .id(customerId)
                .email("customer@example.com")
                .passwordHash("$2a$12$oldHashedPassword")
                .role(customerRole)
                .isActive(true)
                .build();

        when(users.findPasswordHash(customerId)).thenReturn(Optional.of(customer.getPasswordHash()));
        when(attempts.authenticate(eq(customerId), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(passwords.matches("OldPass123@", customer.getPasswordHash())).thenReturn(true);
        when(users.findOwnerForUpdate(customerId)).thenReturn(Optional.of(customer));
        PasswordChangeRequest request = new PasswordChangeRequest("OldPass123@", "NewStrongP@ss123", "NewStrongP@ss123");

        assertThatThrownBy(() -> service.changeStaffPassword(customerId, request, "session-1", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("changeStaffPassword should reject wrong current password")
    void changeStaffPassword_WrongCurrentPassword() {
        Long staffId = 15L;
        User staff = User.builder()
                .id(staffId)
                .passwordHash("$2a$12$oldHashedPassword")
                .role(staffRole)
                .build();

        when(attempts.authenticate(eq(staffId), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(users.findPasswordHash(staffId)).thenReturn(Optional.of(staff.getPasswordHash()));
        when(passwords.matches("WrongPassword123@", "$2a$12$oldHashedPassword")).thenReturn(false);

        PasswordChangeRequest request = new PasswordChangeRequest("WrongPassword123@", "NewStrongP@ss123", "NewStrongP@ss123");

        assertThatThrownBy(() -> service.changeStaffPassword(staffId, request, "session-1", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.PASSWORD_NOT_MATCH));
    }

    @Test
    @DisplayName("changeStaffPassword should reject same new password")
    void changeStaffPassword_SameNewPassword() {
        Long staffId = 15L;
        User staff = User.builder()
                .id(staffId)
                .passwordHash("$2a$12$oldHashedPassword")
                .role(staffRole)
                .build();

        when(attempts.authenticate(eq(staffId), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(users.findPasswordHash(staffId)).thenReturn(Optional.of(staff.getPasswordHash()));
        when(users.findOwnerForUpdate(staffId)).thenReturn(Optional.of(staff));
        when(passwords.matches("OldPassword123@", "$2a$12$oldHashedPassword")).thenReturn(true);

        PasswordChangeRequest request = new PasswordChangeRequest("OldPassword123@", "OldPassword123@", "OldPassword123@");

        assertThatThrownBy(() -> service.changeStaffPassword(staffId, request, "session-1", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA));
    }

    @Test
    @DisplayName("changeStaffPassword should preserve leading and trailing whitespace in raw password without stripping")
    void changeStaffPassword_PreservesWhitespaceInRawPassword() {
        Long staffId = 15L;
        User staff = User.builder()
                .id(staffId)
                .email("staff@aureliabook.vn")
                .passwordHash("$2a$12$oldHashedPassword")
                .role(staffRole)
                .isActive(true)
                .build();

        String rawPasswordWithSpaces = " Abc123! ";
        when(attempts.authenticate(eq(staffId), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(users.findPasswordHash(staffId)).thenReturn(Optional.of(staff.getPasswordHash()));
        when(users.findOwnerForUpdate(staffId)).thenReturn(Optional.of(staff));
        when(passwords.matches("OldPassword123@", "$2a$12$oldHashedPassword")).thenReturn(true);
        when(passwords.matches(rawPasswordWithSpaces, "$2a$12$oldHashedPassword")).thenReturn(false);
        when(passwords.encode(rawPasswordWithSpaces)).thenReturn("$2a$12$hashedWithSpaces");
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        PasswordChangeRequest request = new PasswordChangeRequest("OldPassword123@", rawPasswordWithSpaces, rawPasswordWithSpaces);
        User result = service.changeStaffPassword(staffId, request, "session-1", "127.0.0.1");

        assertThat(result.getPasswordHash()).isEqualTo("$2a$12$hashedWithSpaces");
        verify(passwords).encode(rawPasswordWithSpaces);
        verify(passwords, never()).encode("Abc123!");
    }

    @Test
    void passwordChangeRejectsHashChangedBetweenVerificationAndLock() {
        long staffId = 15L;
        var locked = User.builder().id(staffId).role(staffRole).passwordHash("newer-hash").build();
        when(users.findPasswordHash(staffId)).thenReturn(Optional.of("old-hash"));
        when(users.findOwnerForUpdate(staffId)).thenReturn(Optional.of(locked));
        when(attempts.authenticate(eq(staffId), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(passwords.matches("RightPass@1", "old-hash")).thenReturn(true);
        var request = new PasswordChangeRequest("RightPass@1", "NewStrongP@ss123", "NewStrongP@ss123");

        assertThatThrownBy(() -> service.changeStaffPassword(staffId, request, "session", "ip"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PASSWORD_NOT_MATCH);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void staffPasswordChangeAndReauthenticationShareAttemptLimit() {
        LoginAttemptService realAttempts = new LoginAttemptService();
        var throttledService = new AccountSecurityServiceImpl(users, roles, passwords, realAttempts, sessions, audit);
        long staffId = 15L;
        User staff = User.builder().id(staffId).role(staffRole).passwordHash("old-hash").build();
        when(users.findPasswordHash(staffId)).thenReturn(Optional.of(staff.getPasswordHash()));
        when(users.findById(staffId)).thenReturn(Optional.of(staff));
        var wrong = new PasswordChangeRequest("WrongPassword123@", "NewStrongP@ss123", "NewStrongP@ss123");

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> throttledService.changeStaffPassword(staffId, wrong, "session", "ip"))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PASSWORD_NOT_MATCH);
        }
        assertThatThrownBy(() -> throttledService.reauthenticateStaff(staffId, wrong.currentPassword()))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED);
        assertThatThrownBy(() -> throttledService.changeStaffPassword(staffId, wrong, "session", "ip"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED);
        verify(passwords, times(5)).matches(wrong.currentPassword(), "old-hash");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("reauthenticateStaff verifies active staff password through LoginAttemptService and locks after 5 consecutive failures")
    void reauthenticateStaff_EnforcesThrottleAndInternalRoleGuards() {
        LoginAttemptService realAttempts = new LoginAttemptService();
        AccountSecurityServiceImpl throttledService = new AccountSecurityServiceImpl(
                users, roles, passwords, realAttempts, sessions, audit);

        Long staffId = 22L;
        User staff = User.builder()
                .id(staffId)
                .email("manager@aureliabook.vn")
                .passwordHash("$2a$12$hash")
                .role(Role.builder().id(2L).roleName("ROLE_MANAGER").build())
                .isActive(true)
                .build();
        when(users.findById(staffId)).thenReturn(Optional.of(staff));
        when(passwords.matches("RightPass@1", "$2a$12$hash")).thenReturn(true);
        when(passwords.matches("WrongPass@1", "$2a$12$hash")).thenReturn(false);

        throttledService.reauthenticateStaff(staffId, "RightPass@1");

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> throttledService.reauthenticateStaff(staffId, "WrongPass@1"))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PASSWORD_NOT_MATCH);
        }
        assertThatThrownBy(() -> throttledService.reauthenticateStaff(staffId, "WrongPass@1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED);

        // Customer and inactive accounts are rejected before checking password
        User customer = User.builder().id(99L).role(customerRole).isActive(true).passwordHash("h").build();
        when(users.findById(99L)).thenReturn(Optional.of(customer));
        assertThatThrownBy(() -> throttledService.reauthenticateStaff(99L, "RightPass@1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        staff.setIsActive(false);
        assertThatThrownBy(() -> service.reauthenticateStaff(staffId, "RightPass@1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    @DisplayName("reauthenticateStaff delegates credential check into LoginAttemptService.authenticate")
    void reauthenticateStaff_DelegatesToAttemptLimiter() {
        User staff = User.builder().id(15L).role(staffRole).isActive(true).passwordHash("$2a$12$hash").build();
        when(users.findById(15L)).thenReturn(Optional.of(staff));
        when(passwords.matches("Correct@1", "$2a$12$hash")).thenReturn(true);
        when(attempts.authenticate(eq(15L), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());

        service.reauthenticateStaff(15L, "Correct@1");

        verify(attempts).authenticate(eq(15L), any());
    }
}
