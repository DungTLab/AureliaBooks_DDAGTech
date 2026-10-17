package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.service.impl.ContactChangeServiceImpl;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.repository.UserRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ContactChangeServiceTest {
    static class Time extends Clock {Instant now=Instant.parse("2026-10-12T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
        void advance(long seconds){now=now.plusSeconds(seconds);}}
    jakarta.validation.ValidatorFactory vf;UserRepository users;ContactOtpDelivery send;ContactChangeWriter writer;ContactChangeService service;Time clock;User user;
    String code,recipient,channel;int deliveries;
    @BeforeEach void setup(){
        vf=Validation.buildDefaultValidatorFactory();users=mock(UserRepository.class);send=mock(ContactOtpDelivery.class);writer=mock(ContactChangeWriter.class);clock=new Time();
        user=User.builder().id(1L).email("old@example.com").phone("0912345678").isActive(true).role(Role.builder().roleName("ROLE_CUSTOMER").build()).build();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        doAnswer(a->{channel=a.getArgument(0);recipient=a.getArgument(1);code=a.getArgument(2);deliveries++;return null;}).when(send).send(anyString(),anyString(),anyString());
        service=new ContactChangeServiceImpl(users,vf.getValidator(),send,writer,clock);
    }
    @AfterEach void cleanup(){vf.close();}
    void startEmail(){service.startDestination(1L,"session","EMAIL","NEW@example.com","203.0.113.1");}
    @Test void emailUsesDestinationProofOnlyAfterControllerReauthentication(){
        startEmail();assertEquals("new@example.com",recipient);verifyNoInteractions(writer);
        assertTrue(service.verify(1L,"session",code,"203.0.113.1").completed());
        verify(writer,times(1)).apply(1L,"old@example.com","0912345678","EMAIL","new@example.com","203.0.113.1");
        assertFalse(service.verify(1L,"session",code,"203.0.113.1").completed());
    }
    @Test void smsCanAuthorizePhoneChange(){
        service.startDestination(1L,"session","PHONE","+84 987654321","203.0.113.1");
        assertEquals("SMS",channel);assertEquals("0987654321",recipient);
        assertTrue(service.verify(1L,"session",code,"203.0.113.1").completed());
        verify(writer).apply(1L,"old@example.com","0912345678","PHONE","0987654321","203.0.113.1");
    }
    @Test void googleMissingPhoneUsesOtpToNewPhone(){
        user.setAuthProvider(User.AuthProvider.GOOGLE);user.setPhone(null);
        service.startDestination(1L,"session","PHONE","0987654321","203.0.113.1");
        assertTrue(service.verify(1L,"session",code,"203.0.113.1").completed());
        verify(writer).apply(1L,"old@example.com",null,"PHONE","0987654321","203.0.113.1");
    }
    @Test void cannotVerifyOrResendFromAnotherSessionOrAccount(){
        startEmail();assertNull(service.view(1L,"other"));
        assertFalse(service.verify(1L,"other",code,"ip").completed());assertFalse(service.verify(2L,"session",code,"ip").completed());
        service.resend(1L,"other","ip");assertEquals(1,deliveries);verifyNoInteractions(writer);
    }
    @Test void wrongCodeHasFiveAttemptLimitAndGlobalLimitSurvivesResend(){
        startEmail();String actual=code;String wrong=actual.equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++)service.verify(1L,"session",wrong,"ip");
        assertEquals(0,service.view(1L,"session").attemptsLeft());
        assertFalse(service.verify(1L,"session",actual,"ip").completed());verifyNoInteractions(writer);
        clock.advance(60);service.resend(1L,"session","ip");
        assertEquals(5,service.view(1L,"session").attemptsLeft());
        for(int i=0;i<5;i++)service.verify(1L,"session",code.equals("000000")?"000001":"000000","ip");
        clock.advance(60);service.resend(1L,"session","ip");
        assertFalse(service.verify(1L,"session",code,"ip").completed());verifyNoInteractions(writer);
    }
    @Test void ttlBoundaryAndResendCooldownAndOldCodeInvalidation(){
        startEmail();String old=code;clock.advance(59);service.resend(1L,"session","ip");assertEquals(1,deliveries);
        clock.advance(1);service.resend(1L,"session","ip");assertEquals(2,deliveries);assertNotEquals(old,code);
        assertFalse(service.verify(1L,"session",old,"ip").completed());
        clock.advance(300);assertFalse(service.verify(1L,"session",code,"ip").completed());verifyNoInteractions(writer);
    }
    @Test void resendCannotExtendReauthenticationGrant(){
        startEmail();clock.advance(300);
        service.resend(1L,"session","ip");assertEquals(1,deliveries);verifyNoInteractions(writer);
    }
    @Test void duplicateAndInvalidValuesNeverSendCode(){
        when(users.existsByEmail("new@example.com")).thenReturn(true);startEmail();
        when(users.existsByPhone("0987654321")).thenReturn(true);
        service.startDestination(1L,"session","PHONE","0987654321","ip");
        service.startDestination(1L,"session","EMAIL","invalid","ip");
        verifyNoInteractions(send,writer);
    }
    @Test void deliveryFailureCannotAuthorizeAnUpdate(){
        doThrow(new IllegalStateException()).when(send).send(anyString(),anyString(),anyString());startEmail();
        assertFalse(service.verify(1L,"session","123456","ip").completed());verifyNoInteractions(writer);
    }
    @Test void uniqueIndexRaceLeavesAccountUnchangedAndConsumesPendingFlow(){
        service.startDestination(1L,"session","PHONE","0987654321","ip");
        doThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate")).when(writer).apply(any(),any(),any(),any(),any(),any());
        assertFalse(service.verify(1L,"session",code,"ip").completed());assertNull(service.view(1L,"session"));
    }
    @Test void sendQuotaCannotBeResetByStartingAgain(){
        for(int i=0;i<6;i++){service.startDestination(1L,"session","PHONE","0987654321","ip");clock.advance(60);}
        assertEquals(5,deliveries);
    }
}
