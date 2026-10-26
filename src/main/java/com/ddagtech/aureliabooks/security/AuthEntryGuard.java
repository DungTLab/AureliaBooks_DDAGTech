package com.ddagtech.aureliabooks.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevent a second sign-in or registration from replacing an authenticated session. */
public final class AuthEntryGuard extends OncePerRequestFilter {
    private static final Set<String> ENTRY_PATHS = Set.of(
            "/auth/login", "/auth/register", "/internal/login", "/auth/google/login", "/auth/google/register",
            "/oauth2/authorization/google");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String path = PortalSecurity.path(request);
        boolean googleCallback = path.startsWith("/login/oauth2/code/");
        boolean reauthentication = (path.equals("/oauth2/authorization/google") || googleCallback)
                && request.getSession(false) != null
                && request.getSession(false).getAttribute("GOOGLE_ACCOUNT_ACTION") instanceof GoogleAccountAction.Intent;
        if (PortalSecurity.signedIn(authentication) && (ENTRY_PATHS.contains(path) || googleCallback) && !reauthentication) {
            response.sendRedirect(request.getContextPath() + (PortalSecurity.internalUser(authentication) ? "/dashboard" : "/"));
            return;
        }
        chain.doFilter(request, response);
    }
}
