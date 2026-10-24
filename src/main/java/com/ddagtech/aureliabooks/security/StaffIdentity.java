package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StaffIdentity {
    private final UserRepository users;

    public Long resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (authentication.getPrincipal() instanceof CustomUserDetails local) {
            return local.getId();
        }
        if (authentication.getPrincipal() instanceof OidcUser google) {
            return users.findByGoogleIdentity(User.AuthProvider.GOOGLE, google.getSubject())
                    .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED)).getId();
        }
        throw new AppException(ErrorCode.UNAUTHENTICATED);
    }
}
