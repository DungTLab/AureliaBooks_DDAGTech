package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.service.AccountSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthenticatedSessionUpdater {

    private final AccountSecurityService security;
    private final SessionRegistry sessions;

    public void refresh(Long owner, HttpServletRequest request, HttpServletResponse response) {
        var principal = new CustomUserDetails(security.customer(owner));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal,
            null,
            principal.getAuthorities()
        );
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        var session = request.getSession();
        sessions.removeSessionInformation(session.getId());
        sessions.registerNewSession(session.getId(), principal);
    }
}
