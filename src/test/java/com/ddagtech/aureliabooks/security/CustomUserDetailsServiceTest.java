package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for {@link CustomUserDetailsService}.
 * Validates dual-identifier lookups (Email/Phone), role authority extraction, and account lock enforcement.
 */
@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService userDetailsService;

    private User sampleCustomer;
    private User sampleLockedUser;

    @BeforeEach
    void setUp() {
        Role customerRole = Role.builder()
                .id(4L)
                .roleName("ROLE_CUSTOMER")
                .description("Customer role")
                .build();

        sampleCustomer = User.builder()
                .id(101L)
                .email("khachhang@aureliabook.vn")
                .phone("0988777666")
                .fullName("Nguyễn Văn Khách")
                .passwordHash("$2a$12$eXampleHashedPasswordForTestOnly")
                .isActive(true)
                .roles(Set.of(customerRole))
                .build();

        sampleLockedUser = User.builder()
                .id(102L)
                .email("locked.user@aureliabook.vn")
                .phone("0911222333")
                .fullName("Tài Khoản Bị Khóa")
                .passwordHash("$2a$12$eXampleHashedPasswordForTestOnly")
                .isActive(false)
                .roles(Set.of(customerRole))
                .build();
    }

    @Test
    @DisplayName("Should successfully load active user details when queried by email")
    void testLoadUserByUsername_EmailSuccess() {
        when(userRepository.findByIdentifierWithRoles("khachhang@aureliabook.vn"))
                .thenReturn(Optional.of(sampleCustomer));

        UserDetails userDetails = userDetailsService.loadUserByUsername("khachhang@aureliabook.vn");

        assertThat(userDetails).isNotNull();
        assertThat(userDetails.getUsername()).isEqualTo("khachhang@aureliabook.vn");
        assertThat(userDetails.isEnabled()).isTrue();
        assertThat(userDetails.isAccountNonLocked()).isTrue();
        assertThat(userDetails.getAuthorities()).hasSize(1);
        assertThat(userDetails.getAuthorities().iterator().next().getAuthority()).isEqualTo("ROLE_CUSTOMER");

        verify(userRepository).findByIdentifierWithRoles("khachhang@aureliabook.vn");
    }

    @Test
    @DisplayName("Should successfully load active user details when queried by mobile phone number")
    void testLoadUserByUsername_PhoneSuccess() {
        when(userRepository.findByIdentifierWithRoles("0988777666"))
                .thenReturn(Optional.of(sampleCustomer));

        UserDetails userDetails = userDetailsService.loadUserByUsername("0988777666");

        assertThat(userDetails).isNotNull();
        assertThat(userDetails.getUsername()).isEqualTo("khachhang@aureliabook.vn");
        assertThat(userDetails.isEnabled()).isTrue();

        verify(userRepository).findByIdentifierWithRoles("0988777666");
    }

    @Test
    @DisplayName("Should throw UsernameNotFoundException when user identifier does not exist")
    void testLoadUserByUsername_NotFound() {
        when(userRepository.findByIdentifierWithRoles("unknown@aureliabook.vn"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("unknown@aureliabook.vn"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("User not found with email or phone: unknown@aureliabook.vn");

        verify(userRepository).findByIdentifierWithRoles("unknown@aureliabook.vn");
    }

    @Test
    @DisplayName("Should throw LockedException when user account is deactivated (is_active = false)")
    void testLoadUserByUsername_AccountLocked() {
        when(userRepository.findByIdentifierWithRoles("locked.user@aureliabook.vn"))
                .thenReturn(Optional.of(sampleLockedUser));

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("locked.user@aureliabook.vn"))
                .isInstanceOf(LockedException.class)
                .hasMessageContaining("User account is locked or deactivated");

        verify(userRepository).findByIdentifierWithRoles("locked.user@aureliabook.vn");
    }
}
