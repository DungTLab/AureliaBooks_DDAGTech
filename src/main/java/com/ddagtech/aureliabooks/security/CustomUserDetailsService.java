package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom UserDetailsService implementation supporting dual-identifier authentication (Email or Phone).
 * Loads user records from the database and maps them to {@link CustomUserDetails}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Loads a user by username, which can be either an email address or mobile phone number.
     *
     * @param identifier user email or phone number
     * @return populated UserDetails principal
     * @throws UsernameNotFoundException if no user matches the identifier
     * @throws LockedException           if the user account has been deactivated (is_active = false)
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        log.debug("Attempting authentication lookup for identifier: {}", identifier);

        User user = userRepository.findByIdentifierWithRoles(identifier)
                .orElseThrow(() -> {
                    log.warn("Authentication failed: No user found for identifier '{}'", identifier);
                    return new UsernameNotFoundException("User not found with email or phone: " + identifier);
                });

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            log.warn("Authentication rejected: Account for '{}' is deactivated (is_active = false)", identifier);
            throw new LockedException("User account is locked or deactivated");
        }

        return new CustomUserDetails(user);
    }
}
