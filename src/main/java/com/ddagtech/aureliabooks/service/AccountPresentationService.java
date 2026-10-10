package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.NavbarAccountView;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccountPresentationService {
    private final UserRepository users;
    /** Session snapshots may predate profile edits; resolve current fields by persistent identity. */
    @Transactional(readOnly = true)
    public NavbarAccountView current(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return null;
        Optional<User> current = Optional.empty();
        if (authentication.getPrincipal() instanceof CustomUserDetails local)
            current = users.findById(local.getId());
        else if (authentication.getPrincipal() instanceof OAuth2User google) {
            String subject = google.getAttribute("sub");
            if (subject != null) current = users.findByGoogleIdentity(User.AuthProvider.GOOGLE, subject);
        }
        return current.filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .map(u -> new NavbarAccountView(u.getFullName(), u.getAvatarUrl())).orElse(null);
    }
}
