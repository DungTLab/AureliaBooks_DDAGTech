package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.AdminStaffEmailOtpRequest;
import com.ddagtech.aureliabooks.dto.response.AdminStaffEmailChallengeView;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.StaffContactServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffContactServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private ContactOtpDelivery delivery;

    @Mock
    private StaffContactWriter writer;

    private AtomicReference<Instant> clockInstant;
    private StaffContactServiceImpl service;

    private Role staffRole;
    private Role customerRole;
    private User activeStaff;

    @BeforeEach
    void setUp() {
        clockInstant = new AtomicReference<>(Instant.parse("2026-11-13T08:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clockInstant.get();
            }
        };
        service = new StaffContactServiceImpl(users, delivery, writer, mutableClock);
        staffRole = Role.builder().id(3L).roleName("ROLE_SALE_STAFF").build();
        customerRole = Role.builder().id(4L).roleName("ROLE_CUSTOMER").build();
        activeStaff = User.builder()
                .id(10L)
                .email("staff@aureliabook.vn")
                .passwordHash("$2a$12$hash")
                .role(staffRole)
                .isActive(true)
                .emailVerified(false)
                .build();
        lenient().when(users.findById(10L)).thenReturn(Optional.of(activeStaff));
    }

    @Test
    @DisplayName("Self email verification issues 6-digit OTP, enforces cooldown & max 5 wrong attempts, and succeeds cleanly")
    void selfVerification_Lifecycle_EnforcesCooldownAttemptsExpiryAndWritesViaWriter() {
        when(users.existsByEmail("new@aureliabook.vn")).thenReturn(false);

        AdminStaffEmailChallengeView started = service.startSelfEmailVerification(
                10L, "sess-1", "New@AureliaBook.VN", "10.0.0.1");

        assertThat(started.targetUserId()).isEqualTo(10L);
        assertThat(started.maskedEmail()).contains("***").endsWith("@aureliabook.vn");
        assertThat(started.expiresSeconds()).isEqualTo(300L);
        assertThat(started.resendSeconds()).isEqualTo(60L);

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(delivery).send(eq("EMAIL"), eq("new@aureliabook.vn"), codeCaptor.capture());
        String firstCode = codeCaptor.getValue();
        assertThat(firstCode).matches("\\d{6}");

        // Cooldown blocks immediate resend
        assertThatThrownBy(() -> service.resendSelfEmailVerification(10L, "sess-1", "10.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_RESEND_COOLDOWN);

        // Wrong session cannot verify
        assertThatThrownBy(() -> service.verifySelfEmail(10L, "other-sess", firstCode, "10.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_INVALID_OR_EXPIRED);

        // Resend after 60s succeeds and invalidates old code
        clockInstant.set(clockInstant.get().plusSeconds(61));
        service.resendSelfEmailVerification(10L, "sess-1", "10.0.0.1");
        verify(delivery, times(2)).send(eq("EMAIL"), eq("new@aureliabook.vn"), codeCaptor.capture());
        String secondCode = codeCaptor.getValue();
        assertThat(secondCode).matches("\\d{6}").isNotEqualTo(firstCode);

        // Correct OTP succeeds
        User verifiedUser = User.builder().id(10L).email("new@aureliabook.vn").emailVerified(true).role(staffRole).isActive(true).build();
        when(writer.applySelfVerifiedEmail(10L, "staff@aureliabook.vn", "$2a$12$hash", "new@aureliabook.vn", "sess-1", "10.0.0.1"))
                .thenReturn(verifiedUser);

        User result = service.verifySelfEmail(10L, "sess-1", secondCode, "10.0.0.1");
        assertThat(result.isEmailVerified()).isTrue();
        assertThat(service.findSelfEmailChallenge(10L, "sess-1")).isEmpty();

        // Replay of used OTP is rejected
        assertThatThrownBy(() -> service.verifySelfEmail(10L, "sess-1", secondCode, "10.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_INVALID_OR_EXPIRED);
    }

    @Test
    @DisplayName("Self email verification locks challenge after 5 wrong OTP attempts and rejects expired OTP after 5 minutes")
    void selfVerification_MaxAttemptsAndExpiry() {
        service.startSelfEmailVerification(10L, "sess-1", "staff@aureliabook.vn", "10.0.0.1");
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(delivery).send(eq("EMAIL"), eq("staff@aureliabook.vn"), codeCaptor.capture());
        String validCode = codeCaptor.getValue();
        String wrongCode = "000000".equals(validCode) ? "111111" : "000000";

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.verifySelfEmail(10L, "sess-1", wrongCode, "10.0.0.1"))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_INVALID_OR_EXPIRED);
        }
        assertThatThrownBy(() -> service.verifySelfEmail(10L, "sess-1", wrongCode, "10.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_MAX_ATTEMPTS_EXCEEDED);
        assertThat(service.findSelfEmailChallenge(10L, "sess-1")).isEmpty();

        // New challenge after 60s then expire after 5 minutes
        clockInstant.set(clockInstant.get().plusSeconds(61));
        service.startSelfEmailVerification(10L, "sess-1", "staff@aureliabook.vn", "10.0.0.1");
        verify(delivery, times(2)).send(eq("EMAIL"), eq("staff@aureliabook.vn"), codeCaptor.capture());
        String expiredValidCode = codeCaptor.getValue();
        clockInstant.set(clockInstant.get().plusSeconds(301));
        assertThatThrownBy(() -> service.verifySelfEmail(10L, "sess-1", expiredValidCode, "10.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_INVALID_OR_EXPIRED);
        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("Self verification rejects customers, inactive staff, duplicate email, and rolls back challenge if SMTP fails")
    void selfVerification_GuardsAndDeliveryFailure() {
        User customer = User.builder().id(99L).email("c@example.com").role(customerRole).isActive(true).build();
        when(users.findById(99L)).thenReturn(Optional.of(customer));
        assertThatThrownBy(() -> service.startSelfEmailVerification(99L, "s1", "c@example.com", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        activeStaff.setIsActive(false);
        assertThatThrownBy(() -> service.startSelfEmailVerification(10L, "s1", "a@aureliabook.vn", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED);

        activeStaff.setIsActive(true);
        when(users.existsByEmail("duplicate@aureliabook.vn")).thenReturn(true);
        assertThatThrownBy(() -> service.startSelfEmailVerification(10L, "s1", "duplicate@aureliabook.vn", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_EXISTED);

        when(users.existsByEmail("smtp-fail@aureliabook.vn")).thenReturn(false);
        doThrow(new IllegalStateException("SMTP down")).when(delivery).send(eq("EMAIL"), anyString(), anyString());
        assertThatThrownBy(() -> service.startSelfEmailVerification(10L, "s1", "smtp-fail@aureliabook.vn", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_DELIVERY_FAILED);
        assertThat(service.findSelfEmailChallenge(10L, "s1")).isEmpty();
    }

    @Test
    @DisplayName("Admin lost-channel email OTP rejects self/customer/unconfirmed identity and applies verified email without mutating password")
    void adminLostChannelOtp_EnforcesGuardsAndAppliesViaWriter() {
        AdminStaffEmailOtpRequest validReq = new AdminStaffEmailOtpRequest("recovered@aureliabook.vn", true, "Staff lost inbox");

        // Self blocked
        assertThatThrownBy(() -> service.startAdminEmailOtp(10L, "admin-s", 10L, validReq, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        // Unconfirmed identity blocked
        assertThatThrownBy(() -> service.startAdminEmailOtp(1L, "admin-s", 10L,
                new AdminStaffEmailOtpRequest("recovered@aureliabook.vn", false, null), "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

        // Customer blocked
        User customer = User.builder().id(50L).email("c@example.com").role(customerRole).isActive(true).build();
        when(users.findById(50L)).thenReturn(Optional.of(customer));
        assertThatThrownBy(() -> service.startAdminEmailOtp(1L, "admin-s", 50L, validReq, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        // Valid admin OTP start -> confirm
        when(users.existsByEmail("recovered@aureliabook.vn")).thenReturn(false);
        AdminStaffEmailChallengeView challenge = service.startAdminEmailOtp(1L, "admin-s", 10L, validReq, "127.0.0.1");
        assertThat(challenge.targetUserId()).isEqualTo(10L);

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(delivery).send(eq("EMAIL"), eq("recovered@aureliabook.vn"), codeCaptor.capture());

        User updated = User.builder().id(10L).email("recovered@aureliabook.vn").emailVerified(true).role(staffRole).isActive(true).build();
        when(writer.applyAdminSupportedEmail(
                1L, 10L, "staff@aureliabook.vn", "$2a$12$hash", "recovered@aureliabook.vn", "Staff lost inbox", "127.0.0.1"))
                .thenReturn(updated);

        User confirmed = service.confirmAdminEmailOtp(1L, "admin-s", 10L, codeCaptor.getValue(), "127.0.0.1");
        assertThat(confirmed.getEmail()).isEqualTo("recovered@aureliabook.vn");
        assertThat(confirmed.isEmailVerified()).isTrue();
        assertThat(service.findAdminEmailChallenge(1L, "admin-s", 10L)).isEmpty();
        verify(writer, never()).applySelfVerifiedEmail(any(), any(), any(), any(), any(), any());
    }
}
