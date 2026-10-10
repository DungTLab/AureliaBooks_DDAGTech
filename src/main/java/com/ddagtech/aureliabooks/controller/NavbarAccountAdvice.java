package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.response.NavbarAccountView;
import com.ddagtech.aureliabooks.service.AccountPresentationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Read current account presentation data; session snapshots may predate profile edits. */
@ControllerAdvice
@RequiredArgsConstructor
public class NavbarAccountAdvice {
    private final ObjectProvider<AccountPresentationService> services;
    @ModelAttribute("navAccount")
    public NavbarAccountView account(Authentication authentication) {
        var service = services.getIfAvailable();
        return service == null ? null : service.current(authentication);
    }
}
