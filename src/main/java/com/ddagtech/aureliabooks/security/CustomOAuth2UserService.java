package com.ddagtech.aureliabooks.security;

import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** UC06: TODO verified email/sub/name/picture, existing-account policy, first-login Customer; no fabricated password. Owner: Lê Tiến Dũng. Sprint 1 scaffold; business implementation pending. */
public abstract class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    // UC06: TODO verified email/sub/name/picture, existing-account policy, first-login Customer; no fabricated password
    // Developer must implement and explicitly register this extension point.
}
