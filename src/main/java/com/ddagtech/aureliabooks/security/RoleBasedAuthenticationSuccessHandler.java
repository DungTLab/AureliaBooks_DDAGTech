package com.ddagtech.aureliabooks.security;

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
                                        Authentication authentication) throws IOException {
        String targetUrl = determineTargetUrl(request, response, authentication);

        if (response.isCommitted()) {
            log.debug("Response has already been committed. Unable to redirect to {}", targetUrl);
            return;
        }

        log.info("User '{}' logged in successfully. Redirecting to: {}", authentication.getName(), targetUrl);
        if (request.getSession(false) != null) {
            request.getSession(false).removeAttribute("GOOGLE_AUTH_ORIGIN");
            request.getSession(false).removeAttribute(org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION);
        }
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
            if (isValidRedirect(request, redirectUrl)) {
                requestCache.removeRequest(request, response);
                return redirectUrl;
            }
        }

        requestCache.removeRequest(request, response);
        return "/";
    }

    private boolean isValidRedirect(HttpServletRequest request, String url) {
        if (url == null) return false;
        try {
            java.net.URI target=java.net.URI.create(url);
            java.net.URI origin=java.net.URI.create(request.getRequestURL().toString());
            String path=target.getPath();
            int targetPort=target.getPort() == -1 ? ("https".equalsIgnoreCase(target.getScheme()) ? 443 : 80) : target.getPort();
            int originPort=origin.getPort() == -1 ? ("https".equalsIgnoreCase(origin.getScheme()) ? 443 : 80) : origin.getPort();
            if (!origin.getScheme().equalsIgnoreCase(target.getScheme()) || target.getHost() == null
                    || !origin.getHost().equalsIgnoreCase(target.getHost()) || targetPort != originPort
                    || target.getUserInfo() != null || path == null) return false;
            String context=request.getContextPath();
            return path.equals(context+"/cart") || path.startsWith(context+"/cart/")
                    || path.equals(context+"/checkout") || path.startsWith(context+"/checkout/")
                    || path.equals(context+"/orders") || path.startsWith(context+"/orders/")
                    || path.equals(context+"/profile") || path.startsWith(context+"/profile/")
                    || path.equals(context+"/account") || path.startsWith(context+"/account/")
                    || path.equals(context+"/products") || path.startsWith(context+"/products/");
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
