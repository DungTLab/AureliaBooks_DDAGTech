package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.BootstrapAdminRequest;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.AdminBootstrapServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminBootstrapServiceTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private ValidatorFactory validation;
    private AdminBootstrapService service;

    @BeforeEach
    void prepare() {
        validation = Validation.buildDefaultValidatorFactory();
        service = new AdminBootstrapServiceImpl(roles, users, passwords, validation.getValidator(), audit);
        when(roles.findByRoleNameForUpdate("ROLE_ADMIN"))
                .thenReturn(Optional.of(Role.builder().id(92L).roleName("ROLE_ADMIN").build()));
    }

    @AfterEach
    void closeValidator() {
        validation.close();
    }

    @Test
    void createsFirstAdminWithRoleLookupAndBcrypt() {
        assertThat(service.initialize(new BootstrapAdminRequest("OWNER@example.test", "Bootstrap391@", null))).isTrue();
        var captor = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captor.capture());
        User user = captor.getValue();
        assertThat(user.getEmail()).isEqualTo("owner@example.test");
        assertThat(user.getRole().getId()).isEqualTo(92L);
        assertThat(passwords.matches("Bootstrap391@", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordHash()).startsWith("$2a$12$");
        assertThat(user.isEmailVerified()).isFalse();
        verify(audit).record(isNull(), eq("ADMIN_BOOTSTRAPPED"), eq("users"), isNull(), anyString(), isNull());
    }

    @Test
    void existingIncludingInactiveAdminSkipsWithoutRequiringOldSecret() {
        when(users.existsByRole_RoleName("ROLE_ADMIN")).thenReturn(true);
        assertThat(service.initialize(new BootstrapAdminRequest("", "", null))).isFalse();
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void refusesToPromoteEmailOrPhoneCollision() {
        when(users.existsByEmail(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.initialize(new BootstrapAdminRequest("owner@example.test", "Bootstrap391@", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts");
        when(users.existsByEmail(anyString())).thenReturn(false);
        when(users.existsByPhone("0912345678")).thenReturn(true);
        assertThatThrownBy(() -> service.initialize(new BootstrapAdminRequest("owner@example.test", "Bootstrap391@", "0912345678")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInvalidCredentialsWithoutEchoingSecrets() {
        for (var request : new BootstrapAdminRequest[]{
            new BootstrapAdminRequest("not-email", "Bootstrap391@", null),
            new BootstrapAdminRequest("owner@example.test", "weak", null),
            new BootstrapAdminRequest("owner@example.test", "A1@" + "x".repeat(70), null),
            new BootstrapAdminRequest("owner@example.test", "Bootstrap391@", "123")}) {
            assertThatThrownBy(() -> service.initialize(request)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(request.password());
        }
        verify(users, never()).saveAndFlush(any());
        assertThat(new BootstrapAdminRequest("owner@example.test", "secret", null).toString()).doesNotContain("secret");
    }

    @Test
    void failsClearlyWhenFoundationalAdminRoleMissing() {
        when(roles.findByRoleNameForUpdate("ROLE_ADMIN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.initialize(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("ROLE_ADMIN");
        verifyNoInteractions(users);
    }
}
