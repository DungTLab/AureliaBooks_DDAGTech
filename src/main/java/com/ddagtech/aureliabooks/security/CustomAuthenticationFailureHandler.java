package com.ddagtech.aureliabooks.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Authentication failure handler categorizing security exceptions
 * into specific URL error parameters for informative user alerts.
 */
@Slf4j
@Component
public class CustomAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String errorParam;

        if (exception instanceof TemporaryLoginLockException) {
            errorParam = "throttled";
        } else if (exception instanceof LockedException) {
            log.warn("Authentication rejected - account locked: {}", exception.getMessage());
            errorParam = "locked";
        } else if (exception instanceof DisabledException) {
            log.warn("Authentication rejected - account disabled: {}", exception.getMessage());
            errorParam = "disabled";
        } else if (exception instanceof BadCredentialsException || exception instanceof UsernameNotFoundException) {
            log.warn("Authentication rejected - invalid credentials: {}", exception.getMessage());
            errorParam = "credentials";
        } else {
            log.warn("Authentication rejected - generic failure: {}", exception.getMessage());
            errorParam = "true";
        }

        String targetUrl = "/auth/login?error=" + errorParam;
        redirectStrategy.sendRedirect(request, response, targetUrl);
    }
}
