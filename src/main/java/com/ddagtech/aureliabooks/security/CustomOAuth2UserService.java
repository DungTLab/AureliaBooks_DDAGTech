package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.service.GoogleSignInService;
import com.ddagtech.aureliabooks.service.RegistrationService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Claims are fetched server-side with Google's token, never from a submitted form. */
@Service
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final RegistrationService registration;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
    private final OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate;
    private final LoginAttemptService attempts;
    private GoogleAccountAction accountAction;
    private GoogleSignInService googleSignIn;

    @Autowired
    public void setAccountAction(GoogleAccountAction accountAction) {
        this.accountAction = accountAction;
    }

    @Autowired
    public void setGoogleSignIn(GoogleSignInService googleSignIn) {
        this.googleSignIn = googleSignIn;
    }

    @Autowired
    public CustomOAuth2UserService(RegistrationService registration, LoginAttemptService attempts) {
        this(registration, new DefaultOAuth2UserService(), new OidcUserService(), attempts);
    }

    CustomOAuth2UserService(
        RegistrationService registration,
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate
    ) {
        this(registration, delegate, new OidcUserService());
    }

    CustomOAuth2UserService(
        RegistrationService registration,
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate,
        OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate
    ) {
        this(registration, delegate, oidcDelegate, new LoginAttemptService());
    }

    CustomOAuth2UserService(
        RegistrationService registration,
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate,
        OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate,
        LoginAttemptService attempts
    ) {
        this.registration = registration;
        this.delegate = delegate;
        this.oidcDelegate = oidcDelegate;
        this.attempts = attempts;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
            throw failure("Nhà cung cấp đăng nhập không được hỗ trợ.");
        }
        OAuth2User claims = delegate.loadUser(request);
        User account = registerClaims(claims);
        return authenticateGoogle(account, () ->
            new DefaultOAuth2User(List.of(authority(account)), claims.getAttributes(), "sub")
        );
    }

    /** Spring validates ID-token signature, issuer, audience and nonce before this hook. */
    public OidcUser loadOidcUser(OidcUserRequest request) {
        if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
            throw failure("Nhà cung cấp đăng nhập không được hỗ trợ.");
        }
        OidcUser claims = oidcDelegate.loadUser(request);
        User account = registerClaims(claims);
        return authenticateGoogle(account, () ->
            new DefaultOidcUser(List.of(authority(account)), claims.getIdToken(), claims.getUserInfo(), "sub")
        );
    }

    private <T> T authenticateGoogle(User account, java.util.function.Supplier<T> principal) {
        try {
            return attempts.authenticate(account.getId(), principal);
        } catch (TemporaryLoginLockException locked) {
            throw failure(locked.getMessage());
        }
    }

    private SimpleGrantedAuthority authority(User account) {
        if (
            account.getRole() == null ||
            account.getRole().getRoleName() == null ||
            !java.util.Set.of("ROLE_CUSTOMER", "ROLE_SALE_STAFF", "ROLE_MANAGER", "ROLE_ADMIN").contains(
                account.getRole().getRoleName()
            )
        ) {
            throw failure("Tài khoản chưa được cấp vai trò hợp lệ. Vui lòng liên hệ quản trị viên.");
        }
        return new SimpleGrantedAuthority(account.getRole().getRoleName());
    }

    private User registerClaims(OAuth2User claims) {
        try {
            User actionAccount = accountAction == null ? null : accountAction.resolve(claims);
            if (actionAccount != null) return actionAccount;
            Object verified = claims.getAttribute("email_verified");
            if (googleSignIn != null) {
                if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
                    throw new IllegalArgumentException("Phiên đăng nhập Google không còn hiệu lực.");
                }
                var request = attributes.getRequest();
                var session = request.getSession();
                var identity = new GoogleSignInService.Identity(
                    claims.getAttribute("sub"),
                    claims.getAttribute("email"),
                    Boolean.TRUE.equals(verified),
                    claims.getAttribute("hd"),
                    claims.getAttribute("name"),
                    claims.getAttribute("picture")
                );
                var outcome = googleSignIn.resolve(identity, session.getId(), HttpRequestUtil.getClientIp(request));
                if (outcome.pendingFlow() != null) {
                    session.setAttribute("GOOGLE_LINK_FLOW", outcome.pendingFlow());
                    throw new GoogleLinkRequiredException();
                }
                session.removeAttribute("GOOGLE_LINK_FLOW");
                if (outcome.newlyLinked()) {
                    session.setAttribute("GOOGLE_LINK_COMPLETED", true);
                }
                return outcome.user();
            }
            return registration.registerGoogle(
                claims.getAttribute("sub"),
                claims.getAttribute("email"),
                Boolean.TRUE.equals(verified),
                claims.getAttribute("name"),
                claims.getAttribute("picture")
            );
        } catch (AppException ex) {
            throw failure(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw failure(ex.getMessage());
        } catch (DataIntegrityViolationException ex) {
            throw failure("Tài khoản vừa được đăng ký. Vui lòng thử lại hoặc dùng phương thức đăng nhập đã đăng ký.");
        }
    }

    private OAuth2AuthenticationException failure(String message) {
        return new OAuth2AuthenticationException(new OAuth2Error("registration_failed"), message);
    }
}
