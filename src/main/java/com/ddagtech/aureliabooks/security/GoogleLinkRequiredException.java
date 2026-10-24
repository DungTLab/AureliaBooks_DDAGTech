package com.ddagtech.aureliabooks.security;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** Stops OAuth authentication until the existing account owner supplies proof. */
public class GoogleLinkRequiredException extends OAuth2AuthenticationException {

    public GoogleLinkRequiredException() {
        super(new OAuth2Error("google_link_required"), "Vui lòng xác minh tài khoản hiện có để liên kết Google.");
    }
}
