package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.service.RegistrationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

/** Claims are fetched server-side with Google's token, never from a submitted form. */
@Service
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private final RegistrationService registration;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
    private final OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate;

    @Autowired
    public CustomOAuth2UserService(RegistrationService registration) {
        this(registration, new DefaultOAuth2UserService());
    }

    CustomOAuth2UserService(RegistrationService registration,
                           OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
        this(registration, delegate, new OidcUserService());
    }

    CustomOAuth2UserService(RegistrationService registration,
                           OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate,
                           OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate) {
        this.registration = registration;
        this.delegate = delegate;
        this.oidcDelegate = oidcDelegate;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
            throw failure("Nhà cung cấp đăng nhập không được hỗ trợ.");
        }
        OAuth2User claims = delegate.loadUser(request);
        User account = registerClaims(claims);
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority(account.getRole().getRoleName())),
                claims.getAttributes(), "sub");
    }

    /** Spring validates ID-token signature, issuer, audience and nonce before this hook. */
    public OidcUser loadOidcUser(OidcUserRequest request) {
        if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
            throw failure("Nhà cung cấp đăng nhập không được hỗ trợ.");
        }
        OidcUser claims = oidcDelegate.loadUser(request);
        User account = registerClaims(claims);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority(account.getRole().getRoleName())),
                claims.getIdToken(), claims.getUserInfo(), "sub");
    }

    private User registerClaims(OAuth2User claims) {
        try {
            Object verified = claims.getAttribute("email_verified");
            return registration.registerGoogle(claims.getAttribute("sub"), claims.getAttribute("email"),
                    Boolean.TRUE.equals(verified), claims.getAttribute("name"), claims.getAttribute("picture"));
        } catch (AppException ex) {
            throw failure(ex.getMessage());
        } catch (DataIntegrityViolationException ex) {
            throw failure("Tài khoản vừa được đăng ký. Vui lòng thử lại hoặc dùng phương thức đăng nhập đã đăng ký.");
        }
    }

    private OAuth2AuthenticationException failure(String message) {
        return new OAuth2AuthenticationException(new OAuth2Error("registration_failed"), message);
    }
}
