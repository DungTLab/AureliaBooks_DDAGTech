package com.ddagtech.aureliabooks.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

/** Authenticate credentials normally, then enforce the selected portal's role boundary. */
public final class PortalAuthenticationProvider implements AuthenticationProvider {
    private final AuthenticationProvider delegate;
    private final boolean internal;

    public PortalAuthenticationProvider(AuthenticationProvider delegate, boolean internal) {
        this.delegate = delegate;
        this.internal = internal;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        Authentication result = delegate.authenticate(authentication);
        boolean allowed = internal ? PortalSecurity.internalUser(result)
                : result != null && result.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()));
        if (!allowed) throw new WrongPortalException();
        return result;
    }

    @Override
    public boolean supports(Class<?> type) {
        return delegate.supports(type);
    }

    public static final class WrongPortalException extends BadCredentialsException {
        private static final long serialVersionUID = 1L;

        public WrongPortalException() {
            super("Use the sign-in portal assigned to this account.");
        }
    }
}
