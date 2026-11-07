package com.ddagtech.aureliabooks.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;

/** Shared portal boundaries for filter chains, entry points and presentation. */
public final class PortalSecurity {
    private static final Set<String> INTERNAL_ROLES = Set.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_SALE_STAFF");

    private PortalSecurity() {}

    public static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    public static boolean internalRequest(HttpServletRequest request) {
        String path = path(request);
        return path.equals("/internal") || path.startsWith("/internal/")
                || path.equals("/dashboard") || path.startsWith("/dashboard/")
                || path.equals("/admin") || path.startsWith("/admin/")
                || path.equals("/manager") || path.startsWith("/manager/")
                || path.equals("/staff") || path.startsWith("/staff/")
                || path.equals("/inventory") || path.startsWith("/inventory/")
                || path.equals("/api/receipts") || path.startsWith("/api/receipts/")
                || path.equals("/api/orders") || path.startsWith("/api/orders/")
                || path.equals("/api/products/quick-create");
    }

    public static boolean internalUser(Authentication authentication) {
        return signedIn(authentication) && authentication.getAuthorities().stream()
                .anyMatch(authority -> INTERNAL_ROLES.contains(authority.getAuthority()));
    }

    public static boolean signedIn(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
