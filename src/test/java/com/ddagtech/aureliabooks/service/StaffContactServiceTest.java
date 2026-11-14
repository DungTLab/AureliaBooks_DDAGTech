package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.AdminStaffEmailChallengeView;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.StaffContactServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import static org.mockito.Mockito.doAnswer;
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
    @DisplayName("Two distinct staff accounts can issue and verify OTP concurrently alongside expired challenge cleanup")
    void twoAccountsSendAndVerifyOtpConcurrentlyWithExpirationCleanup() throws Exception {
        User staff11 = User.builder()
                .id(11L)
                .email("staff11@aureliabook.vn")
                .passwordHash("$2a$12$hash11")
                .role(staffRole)
                .isActive(true)
                .emailVerified(false)
                .build();
        User expiredStaff12 = User.builder()
                .id(12L)
                .email("staff12@aureliabook.vn")
                .passwordHash("$2a$12$hash12")
                .role(staffRole)
                .isActive(true)
                .emailVerified(false)
                .build();
        when(users.findById(11L)).thenReturn(Optional.of(staff11));
        when(users.findById(12L)).thenReturn(Optional.of(expiredStaff12));
        when(users.existsByEmail(anyString())).thenReturn(false);

        // Seed an expired flow on a third staff user so concurrent starts invoke purgeExpiredPending()
        service.startSelfEmailVerification(12L, "sess-12", "s12@aureliabook.vn", "10.0.0.12");
        clockInstant.set(clockInstant.get().plusSeconds(305));

        Map<String, String> sentCodes = new ConcurrentHashMap<>();
        CountDownLatch bothDelivering = new CountDownLatch(2);
        CountDownLatch startVerifyLatch = new CountDownLatch(1);
        doAnswer(inv -> {
            String recipient = inv.getArgument(1);
            String code = inv.getArgument(2);
            sentCodes.put(recipient, code);
            if ("s10@aureliabook.vn".equals(recipient) || "s11@aureliabook.vn".equals(recipient)) {
                bothDelivering.countDown();
                bothDelivering.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(delivery).send(eq("EMAIL"), anyString(), anyString());

        when(writer.applySelfVerifiedEmail(eq(10L), any(), any(), eq("s10@aureliabook.vn"), eq("sess-10"), eq("10.0.0.10")))
                .thenReturn(User.builder().id(10L).email("s10@aureliabook.vn").emailVerified(true).role(staffRole).isActive(true).build());
        when(writer.applySelfVerifiedEmail(eq(11L), any(), any(), eq("s11@aureliabook.vn"), eq("sess-11"), eq("10.0.0.11")))
                .thenReturn(User.builder().id(11L).email("s11@aureliabook.vn").emailVerified(true).role(staffRole).isActive(true).build());

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<AdminStaffEmailChallengeView> f10 = pool.submit(
                    (Callable<AdminStaffEmailChallengeView>) () ->
                            service.startSelfEmailVerification(10L, "sess-10", "s10@aureliabook.vn", "10.0.0.10"));
            Future<AdminStaffEmailChallengeView> f11 = pool.submit(
                    (Callable<AdminStaffEmailChallengeView>) () ->
                            service.startSelfEmailVerification(11L, "sess-11", "s11@aureliabook.vn", "10.0.0.11"));

            AdminStaffEmailChallengeView c10 = f10.get(5, TimeUnit.SECONDS);
            AdminStaffEmailChallengeView c11 = f11.get(5, TimeUnit.SECONDS);
            assertThat(c10.targetUserId()).isEqualTo(10L);
            assertThat(c11.targetUserId()).isEqualTo(11L);
            assertThat(service.findSelfEmailChallenge(12L, "sess-12")).isEmpty();

            Future<User> v10 = pool.submit(() -> {
                startVerifyLatch.await(5, TimeUnit.SECONDS);
                return service.verifySelfEmail(10L, "sess-10", sentCodes.get("s10@aureliabook.vn"), "10.0.0.10");
            });
            Future<User> v11 = pool.submit(() -> {
                startVerifyLatch.await(5, TimeUnit.SECONDS);
                return service.verifySelfEmail(11L, "sess-11", sentCodes.get("s11@aureliabook.vn"), "10.0.0.11");
            });
            startVerifyLatch.countDown();

            assertThat(v10.get(5, TimeUnit.SECONDS).getEmail()).isEqualTo("s10@aureliabook.vn");
            assertThat(v11.get(5, TimeUnit.SECONDS).getEmail()).isEqualTo("s11@aureliabook.vn");
        }

        assertThat(service.findSelfEmailChallenge(10L, "sess-10")).isEmpty();
        assertThat(service.findSelfEmailChallenge(11L, "sess-11")).isEmpty();
    }

    @Test
    void rejectsVerificationWhileResendIsInFlight() throws Exception {
        String email = activeStaff.getEmail();
        activeStaff.setEmailVerified(true);
        service.startSelfEmailVerification(10L, "session", email, "10.0.0.1");
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(delivery).send(eq("EMAIL"), eq(email), code.capture());
        String previousCode = code.getValue();
        clockInstant.updateAndGet(now -> now.plusSeconds(60));
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> renewedCode = new AtomicReference<>();
        doAnswer(invocation -> {
            renewedCode.set(invocation.getArgument(2));
            sending.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(delivery).send(eq("EMAIL"), eq(email), anyString());

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var resend = pool.submit(() -> service.resendSelfEmailVerification(10L, "session", "10.0.0.1"));
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.verifySelfEmail(10L, "session", previousCode, "10.0.0.1"))
                        .isInstanceOf(AppException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_INVALID_OR_EXPIRED);
                verifyNoInteractions(writer);
            } finally {
                release.countDown();
            }
            resend.get(5, TimeUnit.SECONDS);
        }

        assertThatThrownBy(() -> service.verifySelfEmail(10L, "session", previousCode, "10.0.0.1"))
                .isInstanceOf(AppException.class);
        service.verifySelfEmail(10L, "session", renewedCode.get(), "10.0.0.1");
        verify(writer).applySelfVerifiedEmail(10L, email, activeStaff.getPasswordHash(), email, "session", "10.0.0.1");
    }

    @Test
    @DisplayName("Concurrent start on the same staff account fails fast while initial SMTP send is in flight")
    void concurrentSameUserStartBlockedWhileDeliveryInFlight() throws Exception {
        when(users.existsByEmail(anyString())).thenReturn(false);
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        doAnswer(inv -> {
            inFlight.countDown();
            releaseDelivery.await(5, TimeUnit.SECONDS);
            return null;
        }).when(delivery).send(eq("EMAIL"), eq("first@aureliabook.vn"), anyString());

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<AdminStaffEmailChallengeView> first = pool.submit(() ->
                    service.startSelfEmailVerification(10L, "sess-1", "first@aureliabook.vn", "10.0.0.1"));

            assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.startSelfEmailVerification(10L, "sess-1", "second@aureliabook.vn", "10.0.0.1"))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTP_RESEND_COOLDOWN);

            releaseDelivery.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).targetUserId()).isEqualTo(10L);
            assertThat(service.findSelfEmailChallenge(10L, "sess-1")).isPresent();
        }
    }
}
