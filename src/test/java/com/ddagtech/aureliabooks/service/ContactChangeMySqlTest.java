package com.ddagtech.aureliabooks.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@EnabledIfSystemProperty(named = "contact.mysql", matches = "true")
class ContactChangeMySqlTest {

    @Autowired
    ContactChangeService changes;

    @Autowired
    ContactChangeWriter writer;

    @Autowired
    UserService profile;

    @Autowired
    RegistrationService registration;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    org.springframework.security.core.session.SessionRegistry sessions;

    @MockitoBean
    ContactOtpDelivery delivery;

    long owner, other;
    String email, otherEmail, code;

    @BeforeEach
    void setup() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("profilefix_qa_");
        email = UUID.randomUUID() + "@example.test";
        otherEmail = UUID.randomUUID() + "@example.test";
        owner = user(email, "0912345001");
        other = user(otherEmail, "0912345002");
        doAnswer(a -> {
            code = a.getArgument(2);
            return null;
        })
            .when(delivery)
            .send(anyString(), anyString(), anyString());
    }

    long user(String mail, String phone) {
        jdbc.update(
            "INSERT INTO users(email,phone,password_hash,full_name,auth_provider,role_id,is_active) VALUES(?,?,?,'QA Customer','LOCAL',4,TRUE)",
            mail,
            phone,
            passwords.encode("QaOldPass1!")
        );
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, mail);
    }

    @AfterEach
    void cleanup() {
        if (owner == 0) return;
        jdbc.update("DELETE FROM audit_logs WHERE user_id IN (?,?)", owner, other);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", owner, other);
    }

    @Test
    void localMissingDobPersistsThenLocks() {
        var dob = LocalDate.now().minusYears(12);
        assertThat(profile.viewProfile(owner).canCompleteDob()).isTrue();
        profile.updateProfile(owner, new ProfileUpdateRequest("QA Customer", null, dob, User.Gender.OTHER));
        assertThat(profile.viewProfile(owner).dob()).isEqualTo(dob);
        assertThat(profile.viewProfile(owner).canCompleteDob()).isFalse();
        assertThatThrownBy(() ->
            profile.updateProfile(
                owner,
                new ProfileUpdateRequest("QA Customer", null, dob.minusDays(1), User.Gender.OTHER)
            )
        ).isInstanceOf(AppException.class);
    }

    @Test
    void emailChangesAfterDestinationProofAndAuditsExactlyOnce() {
        String target = UUID.randomUUID() + "@example.test";
        changes.startDestination(owner, "s", "EMAIL", target, "203.0.113.1");
        assertThat(profile.viewProfile(owner).email()).isEqualTo(email);
        assertThat(changes.verify(owner, "s", code, "203.0.113.1").completed()).isTrue();
        assertThat(profile.viewProfile(owner).email()).isEqualTo(target);
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE user_id=? AND action='PROFILE_EMAIL_CHANGED'",
                Integer.class,
                owner
            )
        ).isEqualTo(1);
        assertThat(changes.verify(owner, "s", code, "203.0.113.1").completed()).isFalse();
    }

    @Test
    void googlePhoneCompletionNeedsDestinationProofAndPreservesIdentity() {
        String sub = "qa-sub-" + owner;
        jdbc.update(
            "UPDATE users SET auth_provider='GOOGLE',provider_id=?,phone=NULL,password_hash=NULL WHERE id=?",
            sub,
            owner
        );
        assertThatThrownBy(() ->
            profile.updateProfile(owner, new ProfileUpdateRequest("QA Customer", "0912345003", null, User.Gender.OTHER))
        ).isInstanceOf(AppException.class);
        changes.startDestination(owner, "s", "PHONE", "0912345003", "ip");
        assertThat(changes.verify(owner, "s", code, "ip").completed()).isTrue();
        assertThat(profile.viewProfile(owner).phone()).isEqualTo("0912345003");
        assertThat(registration.registerGoogle(sub, email, true, "QA Customer", null).getId()).isEqualTo(owner);
    }

    @Test
    void existingEmailAndPhoneAreRejectedAndUniqueIndexesRemainAuthoritative() {
        assertThatThrownBy(() -> writer.apply(owner, email, "0912345001", "EMAIL", otherEmail, "ip")).isInstanceOf(
            AppException.class
        );
        assertThatThrownBy(() -> writer.apply(owner, email, "0912345001", "PHONE", "0912345002", "ip")).isInstanceOf(
            AppException.class
        );
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET email=? WHERE id=?", otherEmail, owner)).isInstanceOf(
            org.springframework.dao.DataIntegrityViolationException.class
        );
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET phone='0912345002' WHERE id=?", owner)).isInstanceOf(
            org.springframework.dao.DataIntegrityViolationException.class
        );
        assertThat(profile.viewProfile(owner).email()).isEqualTo(email);
    }

    @Test
    void staleAccountSnapshotCannotBeOverwritten() {
        changes.startDestination(owner, "s", "PHONE", "0912345003", "ip");
        jdbc.update("UPDATE users SET phone='0912345004' WHERE id=?", owner);
        assertThat(changes.verify(owner, "s", code, "ip").completed()).isFalse();
        assertThat(profile.viewProfile(owner).phone()).isEqualTo("0912345004");
    }

    @Test
    void concurrentReplayCommitsAtMostOnce() throws Exception {
        String target = UUID.randomUUID() + "@example.test";
        changes.startDestination(owner, "s", "EMAIL", target, "ip");
        String finalCode = code;
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> changes.verify(owner, "s", finalCode, "ip").completed());
            var b = pool.submit(() -> changes.verify(owner, "s", finalCode, "ip").completed());
            assertThat(
                List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
                    .stream()
                    .filter(Boolean::booleanValue)
                    .count()
            ).isEqualTo(1);
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE user_id=? AND action='PROFILE_EMAIL_CHANGED'",
                    Integer.class,
                    owner
                )
            ).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void verifyingUnchangedContactsKeepsRegisteredSessionAndDoesNotRequireLogin() {
        var sessionId = "same-contact-" + owner;
        var account = User.builder()
            .id(owner)
            .email(email)
            .phone("0912345001")
            .isActive(true)
            .role(com.ddagtech.aureliabooks.entity.Role.builder().roleName("ROLE_CUSTOMER").build())
            .build();
        sessions.registerNewSession(sessionId, new com.ddagtech.aureliabooks.security.CustomUserDetails(account));
        try {
            for (String type : List.of("EMAIL", "PHONE")) {
                changes.startDestination(owner, "s", type, "EMAIL".equals(type) ? email : "0912345001", "ip");
                var result = changes.verify(owner, "s", code, "ip");
                assertThat(result.completed()).isTrue();
                assertThat(result.loginRequired()).isFalse();
                assertThat(sessions.getSessionInformation(sessionId).isExpired()).isFalse();
            }
            assertThat(
                jdbc.queryForObject(
                    "SELECT email_verified AND phone_verified FROM users WHERE id=?",
                    Boolean.class,
                    owner
                )
            ).isTrue();
        } finally {
            sessions.removeSessionInformation(sessionId);
        }
    }
}
