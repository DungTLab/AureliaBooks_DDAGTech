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
        verify(audit).record(eq(20L), eq("INTERNAL_EMAIL_VERIFIED"), eq("users"), eq(20L), jsonCaptor.capture(), eq("10.0.0.5"));
        String json = jsonCaptor.getValue();
        assertThat(json).contains("\"email_verified\":true", "\"email_changed\":true", "\"verification_channel\":\"EMAIL\"");
        assertThat(json.toLowerCase()).doesNotContain("password", "otp", "token", "hash");
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
    @DisplayName("applyAdminSupportedEmail updates verified email, leaves password untouched, expires all target sessions, and logs cleanly")
    void applyAdminSupportedEmail_Success() {
        User staff = User.builder()
                .id(30L)
                .email("lost@oldmail.vn")
                .passwordHash("$2a$12$untouchedHash")
                .role(managerRole)
                .isActive(true)
                .emailVerified(false)
                .build();

        CustomUserDetails principal = new CustomUserDetails(staff);
        SessionInformation s1 = mock(SessionInformation.class);

        when(users.findOwnerForUpdate(30L)).thenReturn(Optional.of(staff));
        when(users.existsByEmail("recovered@aureliabook.vn")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessions.getAllPrincipals()).thenReturn(List.of(principal));
        when(sessions.getAllSessions(principal, false)).thenReturn(List.of(s1));

        User updated = writer.applyAdminSupportedEmail(
                1L,
                30L,
                "lost@oldmail.vn",
                "$2a$12$untouchedHash",
                "recovered@aureliabook.vn",
                "Staff lost company inbox",
                "192.168.1.2"
        );

        assertThat(updated.getEmail()).isEqualTo("recovered@aureliabook.vn");
        assertThat(updated.isEmailVerified()).isTrue();
        assertThat(updated.getPasswordHash()).isEqualTo("$2a$12$untouchedHash");
        verify(s1).expireNow();

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(1L), eq("ADMIN_STAFF_CONTACT_UPDATED"), eq("users"), eq(30L), jsonCaptor.capture(), eq("192.168.1.2"));
        String json = jsonCaptor.getValue();
        assertThat(json).contains("\"identity_confirmed\":true", "\"email_verified\":true", "\"verification_channel\":\"EMAIL_VERIFICATION\"");
        assertThat(json.toLowerCase()).doesNotContain("password", "otp", "token", "hash");
    }

    @Test
    @DisplayName("applyAdminSupportedEmail rejects self-targeting and customer targets")
    void applyAdminSupportedEmail_RejectsSelfAndCustomer() {
        assertThatThrownBy(() -> writer.applyAdminSupportedEmail(
                1L, 1L, "a@b.vn", "hash", "c@d.vn", "reason", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        User customer = User.builder().id(50L).role(customerRole).isActive(true).email("c@example.com").passwordHash("hash").build();
        when(users.findOwnerForUpdate(50L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> writer.applyAdminSupportedEmail(
                1L, 50L, "c@example.com", "hash", "c2@example.com", "reason", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }
}
