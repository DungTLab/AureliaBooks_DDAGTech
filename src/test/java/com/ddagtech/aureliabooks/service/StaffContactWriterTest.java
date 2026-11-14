package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.impl.StaffContactWriterImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffContactWriterTest {

    @Mock
    private UserRepository users;

    @Mock
    private SessionRegistry sessions;

    @Mock
    private AuditLogService audit;

    private StaffContactWriterImpl writer;
    private Role managerRole;
    private Role customerRole;

    @BeforeEach
    void setUp() {
        writer = new StaffContactWriterImpl(users, sessions, audit, new ObjectMapper());
        managerRole = Role.builder().id(2L).roleName("ROLE_MANAGER").build();
        customerRole = Role.builder().id(4L).roleName("ROLE_CUSTOMER").build();
    }

    @Test
    @DisplayName("applySelfVerifiedEmail updates email and flag without altering password hash, expires other sessions, and records clean audit")
    void applySelfVerifiedEmail_Success_KeepsActiveSessionAndHash() {
        User staff = User.builder()
                .id(20L)
                .email("old@aureliabook.vn")
                .passwordHash("$2a$12$fixedHash")
                .role(managerRole)
                .isActive(true)
                .emailVerified(false)
                .phoneVerified(false)
                .build();

        CustomUserDetails principal = new CustomUserDetails(staff);
        SessionInformation currentSession = mock(SessionInformation.class);
        when(currentSession.getSessionId()).thenReturn("sess-active");
        SessionInformation otherSession = mock(SessionInformation.class);
        when(otherSession.getSessionId()).thenReturn("sess-old");

        when(users.findOwnerForUpdate(20L)).thenReturn(Optional.of(staff));
        when(users.existsByEmail("new.work@aureliabook.vn")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessions.getAllPrincipals()).thenReturn(List.of(principal));
        when(sessions.getAllSessions(principal, false)).thenReturn(List.of(currentSession, otherSession));

        User updated = writer.applySelfVerifiedEmail(
                20L,
                "old@aureliabook.vn",
                "$2a$12$fixedHash",
                "new.work@aureliabook.vn",
                "sess-active",
                "10.0.0.5"
        );

        assertThat(updated.getEmail()).isEqualTo("new.work@aureliabook.vn");
        assertThat(updated.isEmailVerified()).isTrue();
        assertThat(updated.isPhoneVerified()).isFalse();
        assertThat(updated.getPasswordHash()).isEqualTo("$2a$12$fixedHash");

        verify(currentSession, never()).expireNow();
        verify(otherSession).expireNow();

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).recordRequired(eq(20L), eq("INTERNAL_EMAIL_VERIFIED"), eq("users"), eq(20L), jsonCaptor.capture(), eq("10.0.0.5"));
        String json = jsonCaptor.getValue();
        assertThat(json).contains(
                "\"email_verified\":true",
                "\"verified_email\":\"new.work@aureliabook.vn\"",
                "\"email_changed\":true",
                "\"verification_channel\":\"EMAIL\""
        );
        assertThat(json.toLowerCase()).doesNotContain("password", "otp", "token", "hash");
    }

    @Test
    void verifiesExistingMixedCaseEmailWithoutTreatingItAsADuplicate() {
        String storedEmail = "Staff.Work@AureliaBook.vn";
        String canonicalEmail = "staff.work@aureliabook.vn";
        String passwordHash = "$2a$12$fixedHash";
        User staff = User.builder()
                .id(20L)
                .email(storedEmail)
                .passwordHash(passwordHash)
                .role(managerRole)
                .isActive(true)
                .build();
        when(users.findOwnerForUpdate(20L)).thenReturn(Optional.of(staff));
        when(users.saveAndFlush(staff)).thenReturn(staff);

        User updated = writer.applySelfVerifiedEmail(
                20L, storedEmail, passwordHash, canonicalEmail, "sess-active", "10.0.0.5");

        assertThat(updated.getEmail()).isEqualTo(canonicalEmail);
        assertThat(updated.isEmailVerified()).isTrue();
        verify(users, never()).existsByEmail(canonicalEmail);
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(audit).recordRequired(eq(20L), eq("INTERNAL_EMAIL_VERIFIED"), eq("users"),
                eq(20L), details.capture(), eq("10.0.0.5"));
        assertThat(details.getValue()).contains("\"email_changed\":false");
    }

    @Test
    @DisplayName("applySelfVerifiedEmail rejects stale hash/email race, customer role, inactive account, and duplicate email")
    void applySelfVerifiedEmail_Guards() {
        User staff = User.builder()
                .id(20L)
                .email("old@aureliabook.vn")
                .passwordHash("$2a$12$fixedHash")
                .role(managerRole)
                .isActive(true)
                .build();
        when(users.findOwnerForUpdate(20L)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> writer.applySelfVerifiedEmail(
                20L, "old@aureliabook.vn", "$2a$12$differentHash", "new@aureliabook.vn", "s1", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

        staff.setIsActive(false);
        assertThatThrownBy(() -> writer.applySelfVerifiedEmail(
                20L, "old@aureliabook.vn", "$2a$12$fixedHash", "new@aureliabook.vn", "s1", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED);

        staff.setIsActive(true);
        staff.setRole(customerRole);
        assertThatThrownBy(() -> writer.applySelfVerifiedEmail(
                20L, "old@aureliabook.vn", "$2a$12$fixedHash", "new@aureliabook.vn", "s1", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        staff.setRole(managerRole);
        when(users.existsByEmail("taken@aureliabook.vn")).thenReturn(true);
        assertThatThrownBy(() -> writer.applySelfVerifiedEmail(
                20L, "old@aureliabook.vn", "$2a$12$fixedHash", "taken@aureliabook.vn", "s1", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_EXISTED);

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("applySelfVerifiedEmail rejects invalid email format cleanly")
    void applySelfVerifiedEmail_RejectsInvalidEmailFormat() {
        User staff = User.builder()
                .id(20L)
                .email("old@aureliabook.vn")
                .passwordHash("$2a$12$fixedHash")
                .role(managerRole)
                .isActive(true)
                .build();
        when(users.findOwnerForUpdate(20L)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> writer.applySelfVerifiedEmail(
                20L, "old@aureliabook.vn", "$2a$12$fixedHash", "not-an-email", "s1", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

        verify(users, never()).saveAndFlush(any());
    }
}
