package com.ddagtech.aureliabooks.aspect;

import com.ddagtech.aureliabooks.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for {@link AuditLogAspect}.
 * Validates bad credentials event logging and declarative @Audited method interception.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogAspectTest {

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AuditLogAspect auditLogAspect;

    @Test
    @DisplayName("handleAuthenticationFailure() should record LOGIN_FAILED audit entry with username")
    void testHandleAuthenticationFailure() {
        Authentication auth = new UsernamePasswordAuthenticationToken("hacker@malicious.com", "wrongpass");
        AuthenticationException ex = new AuthenticationException("Bad credentials") {};
        AuthenticationFailureBadCredentialsEvent event = new AuthenticationFailureBadCredentialsEvent(auth, ex);

        auditLogAspect.handleAuthenticationFailure(event);

        verify(auditLogService).record(
                isNull(),
                eq("LOGIN_FAILED"),
                eq("users"),
                isNull(),
                contains("hacker@malicious.com"),
                anyString()
        );
    }

    @Test
    @DisplayName("logAuditedAction() should proceed and record audited event")
    void testLogAuditedAction() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toShortString()).thenReturn("MockService.doSomething()");
        when(joinPoint.proceed()).thenReturn("result-success");

        Audited audited = mock(Audited.class);
        when(audited.action()).thenReturn("TEST_ACTION");
        when(audited.targetTable()).thenReturn("test_table");

        Object result = auditLogAspect.logAuditedAction(joinPoint, audited);

        assertThat(result).isEqualTo("result-success");
        verify(auditLogService).record(
                isNull(),
                eq("TEST_ACTION"),
                eq("test_table"),
                isNull(),
                contains("MockService.doSomething()"),
                anyString()
        );
    }
}
