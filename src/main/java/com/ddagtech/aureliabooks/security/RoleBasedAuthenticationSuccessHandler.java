package com.ddagtech.aureliabooks.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;

/**
 * Authentication success handler that performs role-tailored landing dispatching.
 * Routes administrative roles (ADMIN, MANAGER, SALE_STAFF) to the Central Operations Dashboard (/dashboard),
 * while routing shoppers (CUSTOMER) back to their saved checkout/browse intent or the storefront homepage.
 */
@Slf4j
@Component
public class RoleBasedAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final RequestCache requestCache = new HttpSessionRequestCache();
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String targetUrl = determineTargetUrl(request, response, authentication);

        if (response.isCommitted()) {
            log.debug("Response has already been committed. Unable to redirect to {}", targetUrl);
            return;
        }

        log.info("User '{}' logged in successfully. Redirecting to: {}", authentication.getName(), targetUrl);
        redirectStrategy.sendRedirect(request, response, targetUrl);
    }

    /**
     * Determines target redirect URL based on granted authorities and intercepted saved requests.
     *
     * @param request        incoming HTTP servlet request
     * @param response       outgoing HTTP servlet response
     * @param authentication active authentication token containing user principal and authorities
     * @return target redirect URL path
     */
    protected String determineTargetUrl(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) {
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();

        boolean isStaffOrAbove = authorities.stream().anyMatch(authority ->
                "ROLE_ADMIN".equals(authority.getAuthority())
                        || "ROLE_MANAGER".equals(authority.getAuthority())
                        || "ROLE_SALE_STAFF".equals(authority.getAuthority()));

        if (isStaffOrAbove) {
            // Administrative users are always directed to the Back-Office Overview Portal (SCR-DSH01)
            requestCache.removeRequest(request, response);
            return "/dashboard";
        }

        // For CUSTOMER, check for an intercepted request (e.g. from /cart or /checkout)
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            String redirectUrl = savedRequest.getRedirectUrl();
            if (isValidRedirect(redirectUrl)) {
                requestCache.removeRequest(request, response);
                return redirectUrl;
            }
        }

        return "/";
    }

    private boolean isValidRedirect(String url) {
        return url != null && !url.contains("/auth/") && !url.contains("/favicon.ico");
    }
}
