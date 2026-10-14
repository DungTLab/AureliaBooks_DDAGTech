package com.ddagtech.aureliabooks.aspect;

import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring AOP Aspect providing declarative, cross-cutting audit event capture for security mutations,
 * bad credentials login failures, and sensitive operations per NFR-10 / UC29.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Intercepts bad credential authentication failures emitted by Spring Security.
     * Records a "LOGIN_FAILED" audit entry with actor null (Anonymous/System) and target table "users".
     *
     * @param event authentication failure event
     */
    @EventListener
    public void handleAuthenticationFailure(AuthenticationFailureBadCredentialsEvent event) {
        try {
            String username = event.getAuthentication() != null ? event.getAuthentication().getName() : "unknown";
            HttpServletRequest request = getCurrentHttpRequest();
            String clientIp = HttpRequestUtil.getClientIp(request);

            Map<String, Object> details = new HashMap<>();
            details.put("attemptedUsername", username);
            details.put("reason", "Bad credentials");

            String detailsJson = objectMapper.writeValueAsString(details);
            auditLogService.record(null, "LOGIN_FAILED", "users", null, detailsJson, clientIp);
            log.info("Recorded LOGIN_FAILED audit event for username={} from IP={}", username, clientIp);
        } catch (Exception ex) {
            log.error("Failed to record bad credentials audit event", ex);
        }
    }

    /**
     * Intercepts service methods annotated with {@link Audited} to automatically record execution details.
     *
     * @param joinPoint join point of the audited method
     * @param audited annotation metadata containing action and targetTable
     * @return original method return value
     * @throws Throwable propagated from underlying target invocation
     */
    @Around("@annotation(audited)")
    public Object logAuditedAction(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            Long actorId = resolveCurrentUserId();
            HttpServletRequest request = getCurrentHttpRequest();
            String clientIp = HttpRequestUtil.getClientIp(request);

            Map<String, Object> payload = new HashMap<>();
            payload.put("method", joinPoint.getSignature().toShortString());
            payload.put("action", audited.action());

            String detailsJson = objectMapper.writeValueAsString(payload);
            auditLogService.record(actorId, audited.action(), audited.targetTable(), null, detailsJson, clientIp);
        } catch (Exception ex) {
            log.warn("Could not capture AOP audit log for action: {}", audited.action(), ex);
        }
        return result;
    }

    /**
     * Resolves the current authenticated user identifier from Spring SecurityContext.
     *
     * @return authenticated user ID, or null if anonymous or not found
     */
    private Long resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        if (auth.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getId();
        }
        return null;
    }

    /**
     * Retrieves the current HttpServletRequest from Spring RequestContextHolder if bound to the active thread.
     *
     * @return current HttpServletRequest or null
     */
    private HttpServletRequest getCurrentHttpRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
