package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Custom implementation of Spring Security {@link UserDetails}.
 * Wraps the domain {@link User} entity and exposes identity attributes for SSR templates.
 */
@Getter
public class CustomUserDetails implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final String password;
    private final String fullName;
    private final String phone;
    private final User.Gender gender;
    private final String avatarUrl;
    private final boolean active;
    private final Set<GrantedAuthority> authorities;

    /**
     * Constructs a CustomUserDetails instance from a domain User entity.
     *
     * @param user the persistent User entity
     */
    public CustomUserDetails(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.password = user.getPasswordHash();
        this.fullName = user.getFullName();
        this.phone = user.getPhone();
        this.gender = user.getGender();
        this.avatarUrl = user.getAvatarUrl();
        this.active = Boolean.TRUE.equals(user.getIsActive());

        if (user.getRole() != null && user.getRole().getRoleName() != null) {
            this.authorities = Collections.singleton(
                    new SimpleGrantedAuthority(user.getRole().getRoleName())
            );
        } else {
            this.authorities = Collections.emptySet();
        }
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    /**
     * Convenience method to check if the principal has a specific role.
     *
     * @param role the role name to check (e.g., "ROLE_ADMIN")
     * @return true if granted, false otherwise
     */
    public boolean hasRole(String role) {
        return authorities.stream()
                .anyMatch(grantedAuthority -> grantedAuthority.getAuthority().equals(role));
    }
}
