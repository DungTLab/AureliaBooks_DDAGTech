package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.entity.User;
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

    public void refreshStaff(User staff, HttpServletRequest request, HttpServletResponse response) {
        if (staff == null) return;
        var principal = new CustomUserDetails(staff);
        refreshWithPrincipal(principal, request, response);
    }

    public void refresh(Long owner, HttpServletRequest request, HttpServletResponse response) {
        var principal = new CustomUserDetails(security.customer(owner));
        refreshWithPrincipal(principal, request, response);
    }

    private void refreshWithPrincipal(CustomUserDetails principal, HttpServletRequest request, HttpServletResponse response) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal,
            null,
            principal.getAuthorities()
        );
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        var session = request.getSession(false);
        if (session != null) {
            sessions.removeSessionInformation(session.getId());
            sessions.registerNewSession(session.getId(), principal);
        }
    }
}
