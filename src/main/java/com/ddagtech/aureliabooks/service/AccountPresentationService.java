package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.NavbarAccountView;
import org.springframework.security.core.Authentication;

/** Application contract for AccountPresentationService; implementation lives in service.impl. */
public interface AccountPresentationService {
    NavbarAccountView current(Authentication authentication);
}
