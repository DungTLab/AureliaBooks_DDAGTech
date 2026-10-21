package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;

@Component
public class GoogleLinkSessionAuthenticator {

    private final SessionAuthenticationStrategy sessions;
    private final RoleBasedAuthenticationSuccessHandler success;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public GoogleLinkSessionAuthenticator(SessionRegistry registry, RoleBasedAuthenticationSuccessHandler success) {
        var concurrent = new ConcurrentSessionControlAuthenticationStrategy(registry);
        concurrent.setMaximumSessions(5);
        this.sessions = new CompositeSessionAuthenticationStrategy(List.of(concurrent,
                new ChangeSessionIdAuthenticationStrategy(), new RegisterSessionAuthenticationStrategy(registry),
                new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository())));
        this.success = success;
    }

    public void login(User user, HttpServletRequest request, HttpServletResponse response) throws IOException {
        CustomerIdentity.requireCustomer(user);
        var principal = new CustomUserDetails(user);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        sessions.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        request.getSession().removeAttribute("GOOGLE_LINK_FLOW");
        request.getSession().setAttribute("GOOGLE_LINK_COMPLETED", true);
        success.onAuthenticationSuccess(request, response, authentication);
    }
}
