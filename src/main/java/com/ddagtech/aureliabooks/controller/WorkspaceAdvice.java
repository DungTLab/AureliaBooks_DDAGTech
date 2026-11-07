package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.security.PortalSecurity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Presentation state shared by internal navigation and role-aware error pages. */
@ControllerAdvice
public class WorkspaceAdvice {
    @ModelAttribute("workspacePath")
    public String path(HttpServletRequest request) {
        return PortalSecurity.path(request);
    }

    @ModelAttribute("internalPage")
    public boolean page(HttpServletRequest request, Authentication authentication) {
        return Boolean.TRUE.equals(request.getAttribute("internalPortalError"))
                || PortalSecurity.internalRequest(request) || (PortalSecurity.path(request).startsWith("/error")
                && PortalSecurity.internalUser(authentication));
    }

    @ModelAttribute("internalWorkspace")
    public boolean internal(Authentication authentication) {
        return PortalSecurity.internalUser(authentication);
    }
}
