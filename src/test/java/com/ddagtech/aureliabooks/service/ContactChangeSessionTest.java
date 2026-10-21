package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.impl.ContactChangeWriterImpl;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactChangeSessionTest {
    UserRepository users;
    AuditLogService audit;
    SessionRegistryImpl sessions;
    ContactChangeWriter writer;
    User owner;

    @BeforeEach void setup() {
        users = mock(UserRepository.class);
        audit = mock(AuditLogService.class);
        sessions = new SessionRegistryImpl();
        owner = User.builder().id(7L).email("old@example.com").phone("0912345678").isActive(true)
                .role(Role.builder().roleName("ROLE_CUSTOMER").build()).build();
        when(users.findOwnerForUpdate(7L)).thenReturn(Optional.of(owner));
        sessions.registerNewSession("current", new CustomUserDetails(owner));
        sessions.registerNewSession("other-device", new CustomUserDetails(owner));
        writer = new ContactChangeWriterImpl(users, audit, sessions);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach void cleanup() { TransactionSynchronizationManager.clearSynchronization(); }

    void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
    }

    @Test void firstEmailAdditionDoesNotExpireEitherSession() {
        owner.setEmail(null);
        writer.apply(7L, null, owner.getPhone(), "EMAIL", "new@example.com", "ip");
        commit();
        assertFalse(sessions.getSessionInformation("current").isExpired());
        assertFalse(sessions.getSessionInformation("other-device").isExpired());
        assertTrue(owner.isEmailVerified());
        verify(audit).record(7L, "PROFILE_EMAIL_CHANGED", "users", 7L, "{\"field\":\"EMAIL\",\"verified\":true}", "ip");
    }

    @Test void firstPhoneAdditionPreservesGoogleIdentityAndSessions() {
        owner.setPhone(null);
        owner.setProviderId("existing-google-sub");
        writer.apply(7L, owner.getEmail(), null, "PHONE", "0987654321", "ip");
        commit();
        assertFalse(sessions.getSessionInformation("current").isExpired());
        assertEquals("existing-google-sub", owner.getProviderId());
        assertTrue(owner.isPhoneVerified());
    }

    @Test void replacingEmailStillExpiresSessionsOnlyAfterCommit() {
        writer.apply(7L, owner.getEmail(), owner.getPhone(), "EMAIL", "new@example.com", "ip");
        assertFalse(sessions.getSessionInformation("current").isExpired());
        commit();
        assertTrue(sessions.getSessionInformation("current").isExpired());
        assertTrue(sessions.getSessionInformation("other-device").isExpired());
    }

    @Test void replacingPhoneStillExpiresSessionsAfterCommit() {
        writer.apply(7L, owner.getEmail(), owner.getPhone(), "PHONE", "0987654321", "ip");
        commit();
        assertTrue(sessions.getSessionInformation("current").isExpired());
    }
}
