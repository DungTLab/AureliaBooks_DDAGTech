package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.service.impl.GoogleSignInServiceImpl;
import java.util.Optional;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleSignInServiceTest {
    UserRepository users;
    RegistrationService registration;
    AccountSecurityService writer;
    AccountOtpService otp;
    GoogleSignInService service;
    LoginAttemptService attempts;
    User owner;

    @BeforeEach
    void setup() {
        users = mock(UserRepository.class);
        registration = mock(RegistrationService.class);
        writer = mock(AccountSecurityService.class);
        otp = mock(AccountOtpService.class);
        attempts = new LoginAttemptService();
        service = new GoogleSignInServiceImpl(users, registration, writer, otp, attempts);
        owner = User.builder().id(7L).email("member@gmail.com").emailVerified(true).isActive(true)
                .role(Role.builder().roleName("ROLE_CUSTOMER").build()).build();
        when(users.findByIdentifierWithRoles(anyString())).thenReturn(Optional.of(owner));
        when(writer.completeGoogleLink(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(owner);
        when(otp.prepareGoogleLink(anyLong(), anyString(), anyString(), anyString())).thenReturn("pending");
    }

    GoogleSignInService.Identity identity(String email, String hostedDomain) {
        return new GoogleSignInService.Identity("google-sub", email, true, hostedDomain, "Member", null);
    }

    @Test void matchingVerifiedGmailAutomaticallyLinksSameAccount() {
        var result = service.resolve(identity(" MEMBER@GMAIL.COM ", null), "session", "ip");
        assertEquals(owner, result.user());
        assertTrue(result.newlyLinked());
        verify(writer).completeGoogleLink(7L, "google-sub", "member@gmail.com", "AUTO", "member@gmail.com", "ip");
        verifyNoInteractions(otp, registration);
    }

    @Test void verifiedWorkspaceDomainMustMatchEmailDomain() {
        assertNotNull(service.resolve(identity("member@example.com", "example.com"), "session", "ip").user());
        assertNull(service.resolve(identity("member@example.com", "other.com"), "session", "ip").user());
        verify(otp).prepareGoogleLink(7L, "google-sub", "member@example.com", "session");
    }

    @Test void thirdPartyGoogleEmailNeedsPendingProof() {
        var result = service.resolve(identity("member@example.com", null), "session", "ip");
        assertNull(result.user());
        assertEquals("pending", result.pendingFlow());
        verifyNoInteractions(writer, registration);
    }

    @Test void unverifiedLocalEmailIsNeverEnoughForAutomaticLink() {
        owner.setEmailVerified(false);
        assertNull(service.resolve(identity(owner.getEmail(), null), "session", "ip").user());
        verifyNoInteractions(writer);
    }

    @Test void inactiveStaffAndConflictingGoogleAccountsAreRejected() {
        owner.setIsActive(false);
        assertThrows(RuntimeException.class, () -> service.resolve(identity(owner.getEmail(), null), "session", "ip"));
        owner.setIsActive(true);
        owner.setRole(Role.builder().roleName("ROLE_ADMIN").build());
        assertThrows(RuntimeException.class, () -> service.resolve(identity(owner.getEmail(), null), "session", "ip"));
        owner.setRole(Role.builder().roleName("ROLE_CUSTOMER").build());
        owner.setProviderId("another-sub");
        assertThrows(IllegalArgumentException.class, () -> service.resolve(identity(owner.getEmail(), null), "session", "ip"));
        verifyNoInteractions(writer, otp);
    }

    @Test void rejectsUnverifiedProviderClaimsBeforeAnyLookup() {
        var claims = new GoogleSignInService.Identity("sub", owner.getEmail(), false, null, "Member", null);
        assertThrows(IllegalArgumentException.class, () -> service.resolve(claims, "session", "ip"));
        verifyNoInteractions(users, writer, otp);
    }

    @Test void temporaryLoginLockCannotBeBypassedByAutomaticLink() {
        for (int i = 0; i < 5; i++) {
            assertThrows(org.springframework.security.core.AuthenticationException.class, () -> attempts.authenticate(7L, () -> {
                throw new org.springframework.security.authentication.BadCredentialsException("wrong");
            }));
        }
        assertThrows(org.springframework.security.core.AuthenticationException.class,
                () -> service.resolve(identity(owner.getEmail(), null), "session", "ip"));
        verifyNoInteractions(writer, otp);
    }

    @Test void alreadyLinkedIdentityUsesExistingSignInAndNewEmailStillUsesRegistration() {
        when(users.findByGoogleIdentity(User.AuthProvider.GOOGLE, "google-sub")).thenReturn(Optional.of(owner));
        when(registration.registerGoogle(any(), any(), any(), any(), any())).thenReturn(owner);
        assertEquals(owner, service.resolve(identity(owner.getEmail(), null), "session", "ip").user());
        verify(users, never()).findByIdentifierWithRoles(anyString());
        when(users.findByGoogleIdentity(User.AuthProvider.GOOGLE, "google-sub")).thenReturn(Optional.empty());
        when(users.findByIdentifierWithRoles(anyString())).thenReturn(Optional.empty());
        assertEquals(owner, service.resolve(identity("new@gmail.com", null), "session", "ip").user());
        verifyNoInteractions(writer, otp);
    }
}
