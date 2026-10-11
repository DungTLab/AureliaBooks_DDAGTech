package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/** Resolves only server-authenticated identities, never request parameters. */
@Component
@RequiredArgsConstructor
public class CustomerIdentity {
    private final UserRepository users;
    public Long resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated())
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        User user;
        if (authentication.getPrincipal() instanceof CustomUserDetails local) {
            user = users.findById(local.getId()).orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        } else if (authentication.getPrincipal() instanceof OidcUser google) {
            user = users.findByGoogleIdentity(User.AuthProvider.GOOGLE, google.getSubject())
                    .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        } else throw new AppException(ErrorCode.UNAUTHENTICATED);
        requireCustomer(user);
        return user.getId();
    }
    public static void requireCustomer(User user) {
        if (!Boolean.TRUE.equals(user.getIsActive())) throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        if (user.getRole() == null || !"ROLE_CUSTOMER".equals(user.getRole().getRoleName()))
            throw new AppException(ErrorCode.UNAUTHORIZED);
    }
}
