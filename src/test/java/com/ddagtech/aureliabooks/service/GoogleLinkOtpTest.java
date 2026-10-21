package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.service.impl.AccountOtpServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleLinkOtpTest {
    ValidatorFactory validators;
    UserRepository users;
    AccountSecurityService writer;
    ContactOtpDelivery delivery;
    AccountOtpService service;
    User owner;
    String code;

    @BeforeEach void setup() {
        validators = Validation.buildDefaultValidatorFactory();
        users = mock(UserRepository.class);
        writer = mock(AccountSecurityService.class);
        delivery = mock(ContactOtpDelivery.class);
        service = new AccountOtpServiceImpl(users, validators.getValidator(), new BCryptPasswordEncoder(4), delivery, writer, new LoginAttemptService());
        owner = User.builder().id(7L).email("member@example.com").emailVerified(true).phone("0912345678")
                .phoneVerified(true).isActive(true).role(Role.builder().roleName("ROLE_CUSTOMER").build()).build();
        when(users.findByIdentifierWithRoles(owner.getEmail())).thenReturn(Optional.of(owner));
        when(writer.completeGoogleLink(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(owner);
        doAnswer(invocation -> { code = invocation.getArgument(2); return null; }).when(delivery).send(anyString(), anyString(), anyString());
    }

    @AfterEach void close() { validators.close(); }

    String prepare() { return service.prepareGoogleLink(7L, "subject", owner.getEmail(), "session"); }

    Object flow(String id) { return ((Map<?, ?>) ReflectionTestUtils.getField(service, "flows")).get(id); }

    @Test void twoVerifiedChannelsRequireExplicitSelectionAndDoNotSendOnPrepare() {
        String id = prepare();
        assertEquals(List.of("EMAIL", "SMS"), service.googleLinkView(id, "session").channels());
        assertThrows(IllegalArgumentException.class, () -> service.sendGoogleLink(id, "session", null, "ip"));
        verifyNoInteractions(delivery, writer);
    }

    @Test void oneVerifiedChannelIsSelectedWithoutTrustingSubmittedChannel() {
        owner.setEmailVerified(false);
        String id = prepare();
        service.sendGoogleLink(id, "session", "EMAIL", "ip");
        verify(delivery).send(eq("SMS"), eq("0912345678"), anyString());
        assertEquals(List.of("SMS"), service.googleLinkView(id, "session").channels());
    }

    @Test void verifiedCodeLinksBoundIdentityOnceAndCannotResetPassword() {
        String id = prepare();
        service.sendGoogleLink(id, "session", "EMAIL", "ip");
        assertThrows(IllegalArgumentException.class, () -> service.verify(id, "session", code, "ip"));
        assertThrows(IllegalArgumentException.class, () -> service.reset(id, "session", "NewPass1!", "NewPass1!", "ip"));
        assertEquals(owner, service.verifyGoogleLink(id, "session", code, "ip"));
        verify(writer).completeGoogleLink(7L, "subject", owner.getEmail(), "EMAIL", owner.getEmail(), "ip");
        assertThrows(IllegalArgumentException.class, () -> service.verifyGoogleLink(id, "session", code, "ip"));
    }

    @Test void pendingProofIsSessionBoundAndProtectedByExpiryAndFiveWrongGuesses() {
        String id = prepare();
        assertNull(service.googleLinkView(id, "other-session"));
        assertThrows(IllegalArgumentException.class, () -> service.sendGoogleLink(id, "other-session", "EMAIL", "ip"));
        service.sendGoogleLink(id, "session", "EMAIL", "ip");
        String wrong = "000000".equals(code) ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalArgumentException.class, () -> service.verifyGoogleLink(id, "session", wrong, "ip"));
        }
        assertThrows(IllegalArgumentException.class, () -> service.verifyGoogleLink(id, "session", code, "ip"));
        verifyNoInteractions(writer);
    }

    @Test void expiredOtpOrGoogleProofCannotLink() {
        String id = prepare();
        service.sendGoogleLink(id, "session", "EMAIL", "ip");
        ReflectionTestUtils.setField(flow(id), "expires", Instant.now().minusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> service.verifyGoogleLink(id, "session", code, "ip"));
        ReflectionTestUtils.setField(flow(id), "googleProofExpires", Instant.now().minusSeconds(1));
        assertNull(service.googleLinkView(id, "session"));
        assertThrows(IllegalArgumentException.class, () -> service.sendGoogleLink(id, "session", "EMAIL", "ip"));
        verifyNoInteractions(writer);
    }

    @Test void cooldownAndResendInvalidatePreviousOtp() {
        String id = prepare();
        service.sendGoogleLink(id, "session", "SMS", "ip");
        String oldCode = code;
        assertThrows(IllegalArgumentException.class, () -> service.sendGoogleLink(id, "session", "EMAIL", "ip"));
        ReflectionTestUtils.setField(flow(id), "sent", Instant.now().minusSeconds(61));
        var limits = (Map<?, ?>) ReflectionTestUtils.getField(service, "limits");
        for (Object limit : limits.values()) ReflectionTestUtils.setField(limit, "lastSent", Instant.now().minusSeconds(61));
        service.sendGoogleLink(id, "session", "SMS", "ip");
        assertNotEquals(oldCode, code);
        assertThrows(IllegalArgumentException.class, () -> service.verifyGoogleLink(id, "session", oldCode, "ip"));
        assertEquals(owner, service.verifyGoogleLink(id, "session", code, "ip"));
    }

    @Test void missingVerifiedChannelsOrChangedAccountCannotContinue() {
        owner.setEmailVerified(false); owner.setPhoneVerified(false);
        assertThrows(IllegalArgumentException.class, this::prepare);
        owner.setPhoneVerified(true);
        String id = prepare();
        owner.setProviderId("different-subject");
        assertThrows(IllegalArgumentException.class, () -> service.sendGoogleLink(id, "session", "SMS", "ip"));
        verifyNoInteractions(delivery, writer);
    }

    @Test void deliveryFailureDoesNotCreateUsableOtp() {
        String id = prepare();
        doThrow(new IllegalStateException("transport unavailable")).when(delivery).send(anyString(), anyString(), anyString());
        assertThrows(IllegalArgumentException.class, () -> service.sendGoogleLink(id, "session", "EMAIL", "ip"));
        assertFalse(service.googleLinkView(id, "session").sent());
        verifyNoInteractions(writer);
    }
}
