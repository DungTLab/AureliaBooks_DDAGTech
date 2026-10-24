package com.ddagtech.aureliabooks.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in: never executes against the application database. */
@SpringBootTest
@EnabledIfSystemProperty(named = "google.link.mysql", matches = "true")
class GoogleLinkMySqlTest {

    @Autowired
    AccountSecurityService writer;

    @Autowired
    GoogleSignInService signIn;

    @Autowired
    AccountOtpService otp;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @MockitoBean
    ContactOtpDelivery delivery;

    Long owner;
    String email;

    @BeforeEach
    void setup() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("google_link_qa_"));
        email = UUID.randomUUID().toString().replace("-", "") + "@gmail.com";
        jdbc.update(
            "INSERT INTO users(email,email_verified,phone_verified,password_hash,full_name,auth_provider,role_id) " +
                "VALUES(?,TRUE,FALSE,'existing-local-hash','Google Link QA','LOCAL',(SELECT id FROM roles WHERE role_name='ROLE_CUSTOMER'))",
            email
        );
        owner = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    long audits() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE action='GOOGLE_LINKED' AND target_id=?",
            Long.class,
            owner
        );
    }

    String subject() {
        return "qa-" + UUID.randomUUID();
    }

    @Test
    void authoritativeGoogleLinksSameAccountWithoutChangingPasswordOrCreatingAccount() {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        String sub = subject();
        var result = signIn.resolve(
            new GoogleSignInService.Identity(sub, email, true, null, "Google Name", null),
            "session",
            "127.0.0.1"
        );
        assertEquals(owner, result.user().getId());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class));
        assertEquals(sub, jdbc.queryForObject("SELECT provider_id FROM users WHERE id=?", String.class, owner));
        assertEquals(
            "existing-local-hash",
            jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, owner)
        );
        assertEquals("LOCAL", jdbc.queryForObject("SELECT auth_provider FROM users WHERE id=?", String.class, owner));
        assertEquals(1, audits());
        writer.completeGoogleLink(owner, sub, email, "AUTO", email, "127.0.0.1");
        assertEquals(1, audits(), "Idempotent linking must not append a duplicate event");
    }

    @Test
    void otpFinalizationRechecksCurrentContactAndConsumesFailedProof() {
        String thirdParty = email.replace("@gmail.com", "@example.com");
        jdbc.update("UPDATE users SET email=? WHERE id=?", thirdParty, owner);
        var code = new AtomicReference<String>();
        doAnswer(call -> {
            code.set(call.getArgument(2));
            return null;
        })
            .when(delivery)
            .send(any(), any(), any());
        var result = signIn.resolve(
            new GoogleSignInService.Identity(subject(), thirdParty, true, null, "Member", null),
            "otp-session",
            "127.0.0.2"
        );
        assertNull(result.user());
        otp.sendGoogleLink(result.pendingFlow(), "otp-session", null, "127.0.0.2");
        jdbc.update("UPDATE users SET email_verified=FALSE WHERE id=?", owner);
        assertThrows(IllegalArgumentException.class, () ->
            otp.verifyGoogleLink(result.pendingFlow(), "otp-session", code.get(), "127.0.0.2")
        );
        assertNull(otp.googleLinkView(result.pendingFlow(), "otp-session"));
        assertNull(jdbc.queryForObject("SELECT provider_id FROM users WHERE id=?", String.class, owner));
        assertEquals(0, audits());
    }

    @Test
    void otpHappyPathUsesExistingAccountAndCanOnlyBeConsumedOnce() {
        String thirdParty = email.replace("@gmail.com", "@example.com");
        jdbc.update("UPDATE users SET email=? WHERE id=?", thirdParty, owner);
        var code = new AtomicReference<String>();
        doAnswer(call -> {
            code.set(call.getArgument(2));
            return null;
        })
            .when(delivery)
            .send(any(), any(), any());
        String sub = subject();
        var result = signIn.resolve(
            new GoogleSignInService.Identity(sub, thirdParty, true, null, "Member", null),
            "happy-session",
            "127.0.0.5"
        );
        otp.sendGoogleLink(result.pendingFlow(), "happy-session", null, "127.0.0.5");
        assertEquals(
            owner,
            otp.verifyGoogleLink(result.pendingFlow(), "happy-session", code.get(), "127.0.0.5").getId()
        );
        assertThrows(IllegalArgumentException.class, () ->
            otp.verifyGoogleLink(result.pendingFlow(), "happy-session", code.get(), "127.0.0.5")
        );
        assertEquals(sub, jdbc.queryForObject("SELECT provider_id FROM users WHERE id=?", String.class, owner));
        assertEquals(
            "existing-local-hash",
            jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, owner)
        );
        assertEquals(1, audits());
    }

    @Test
    void competingGoogleIdentitiesCannotOverwriteEachOther() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> link = () -> {
                start.await();
                try {
                    writer.completeGoogleLink(owner, subject(), email, "AUTO", email, "127.0.0.3");
                    return true;
                } catch (IllegalArgumentException conflict) {
                    return false;
                }
            };
            var first = executor.submit(link);
            var second = executor.submit(link);
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, audits());
    }

    @Test
    void identityClaimedByAnotherAccountWhileOtpPendingIsRejected() {
        String thirdParty = email.replace("@gmail.com", "@example.com");
        jdbc.update("UPDATE users SET email=? WHERE id=?", thirdParty, owner);
        var code = new AtomicReference<String>();
        doAnswer(call -> {
            code.set(call.getArgument(2));
            return null;
        })
            .when(delivery)
            .send(any(), any(), any());
        String sub = subject();
        var result = signIn.resolve(
            new GoogleSignInService.Identity(sub, thirdParty, true, null, "Member", null),
            "conflict-session",
            "127.0.0.6"
        );
        otp.sendGoogleLink(result.pendingFlow(), "conflict-session", null, "127.0.0.6");
        String otherEmail = UUID.randomUUID() + "@example.com";
        jdbc.update(
            "INSERT INTO users(email,email_verified,full_name,auth_provider,provider_id,role_id) VALUES(?,TRUE,'Other QA','GOOGLE',?,(SELECT id FROM roles WHERE role_name='ROLE_CUSTOMER'))",
            otherEmail,
            sub
        );
        assertThrows(IllegalArgumentException.class, () ->
            otp.verifyGoogleLink(result.pendingFlow(), "conflict-session", code.get(), "127.0.0.6")
        );
        assertNull(jdbc.queryForObject("SELECT provider_id FROM users WHERE id=?", String.class, owner));
        assertEquals(0, audits());
    }

    @Test
    void matchingEmailIsCaseInsensitiveWithoutRewritingStoredContact() {
        String stored = email.toUpperCase(java.util.Locale.ROOT);
        jdbc.update("UPDATE users SET email=? WHERE id=?", stored, owner);
        var result = signIn.resolve(
            new GoogleSignInService.Identity(subject(), email, true, null, "Member", null),
            "case-session",
            "127.0.0.7"
        );
        assertEquals(owner, result.user().getId());
        assertEquals(stored, jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, owner));
        assertEquals(1, audits());
    }

    @Test
    void rolledBackLinkDoesNotPersistIdentityOrAuditAndInactiveAccountCannotLink() {
        var transaction = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () ->
            transaction.execute(status -> {
                writer.completeGoogleLink(owner, subject(), email, "AUTO", email, "127.0.0.4");
                throw new IllegalStateException("QA forced rollback");
            })
        );
        assertNull(jdbc.queryForObject("SELECT provider_id FROM users WHERE id=?", String.class, owner));
        assertEquals(0, audits());
        jdbc.update("UPDATE users SET is_active=FALSE WHERE id=?", owner);
        assertThrows(RuntimeException.class, () ->
            writer.completeGoogleLink(owner, subject(), email, "AUTO", email, "127.0.0.4")
        );
        assertEquals(0, audits());
    }
}
