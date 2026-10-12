package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LogoutAuditHandler implements LogoutHandler {
    private final AuditLogService audit;
    private final UserRepository users;

    @Override public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) return;
        try {
            Long id=null;
            String method="LOCAL";
            if (authentication.getPrincipal() instanceof CustomUserDetails local) id=local.getId();
            else if (authentication.getPrincipal() instanceof OAuth2User google) {
                method="GOOGLE";
                id=users.findByGoogleIdentity(User.AuthProvider.GOOGLE,google.getAttribute("sub"))
                        .map(User::getId).orElse(null);
            }
            if (id != null) audit.record(id,"LOGOUT","users",id,
                    "{\"method\":\""+method+"\"}",request.getRemoteAddr());
        } catch (RuntimeException failure) {
            // Audit failure must not prevent SecurityContext/session cleanup.
            log.error("Could not record logout audit",failure);
        }
    }
}
