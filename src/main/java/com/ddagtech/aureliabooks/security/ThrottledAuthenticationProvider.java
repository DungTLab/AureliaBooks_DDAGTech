package com.ddagtech.aureliabooks.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Wraps Spring's BCrypt authentication with the account-level NFR-09 guard. */
public class ThrottledAuthenticationProvider implements AuthenticationProvider {
    private final UserDetailsService users;
    private final DaoAuthenticationProvider delegate;
    private final PasswordEncoder encoder;
    private final LoginAttemptService attempts;

    public ThrottledAuthenticationProvider(UserDetailsService users, PasswordEncoder encoder, LoginAttemptService attempts) {
        this.users=users;
        this.encoder=encoder;
        this.attempts=attempts;
        delegate=new DaoAuthenticationProvider(users);
        delegate.setPasswordEncoder(encoder);
    }

    @Override public Authentication authenticate(Authentication authentication) {
        final CustomUserDetails account;
        try {
            account=(CustomUserDetails)users.loadUserByUsername(authentication.getName());
        } catch (UsernameNotFoundException missing) {
            // Preserve Spring's dummy password check and generic invalid-credentials response.
            return delegate.authenticate(authentication);
        }
        return attempts.authenticate(account.getId(), () -> {
            // Reuse the freshly loaded principal: no second database lookup per known-account attempt.
            DaoAuthenticationProvider provider=new DaoAuthenticationProvider(ignored -> account);
            provider.setPasswordEncoder(encoder);
            return provider.authenticate(authentication);
        });
    }

    @Override public boolean supports(Class<?> authentication) { return delegate.supports(authentication); }
}
