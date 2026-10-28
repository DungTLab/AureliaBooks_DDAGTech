package com.ddagtech.aureliabooks.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.AuthController;
import com.ddagtech.aureliabooks.controller.InternalPortalController;
import com.ddagtech.aureliabooks.controller.admin.DashboardController;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.service.AccountOtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@WebMvcTest(controllers = {AuthController.class, InternalPortalController.class, DashboardController.class, com.ddagtech.aureliabooks.controller.CustomErrorController.class})
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        CustomAuthenticationFailureHandler.class, RoleBasedAuthenticationSuccessHandler.class})
class PortalSeparationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired FilterChainProxy filters;
    @MockitoBean CustomUserDetailsService users;
    @MockitoBean AccountOtpService otp;
    @MockitoBean com.ddagtech.aureliabooks.service.ProductService products;
    @MockitoBean StaffIdentity staffIdentity;
    @MockitoBean com.ddagtech.aureliabooks.service.AccountSecurityService accountSecurityService;
    @MockitoBean AuthenticatedSessionUpdater sessionUpdater;

    @BeforeEach
    void credentials() {
        String hash = new BCryptPasswordEncoder(4).encode("PortalExample391@");
        when(users.loadUserByUsername(anyString())).thenAnswer(call -> {
            String role = call.getArgument(0);
            return new CustomUserDetails(User.builder().id((long) role.hashCode()).email(role)
                    .fullName("Portal QA").isActive(true).passwordHash(hash)
                    .role(Role.builder().roleName("ROLE_" + role).build()).build());
        });
    }

    @Test
    void orderedChainsAreDistinct() {
        assertThat(filters.getFilterChains()).hasSize(2);
    }

    @Test
    void internalLoginRendersIndependentShellWithoutShopControls() throws Exception {
        String html = mvc.perform(get("/internal/login")).andExpect(status().isOk())
                .andExpect(view().name("internal/login")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-portal=\"internal\"", "name=\"_csrf\"", "/internal/login", "/css/workspace.css")
                .doesNotContain("/js/cart.js", "/auth/google/login", "/auth/register", "name=\"keyword\"");
    }

    @Test
    void internalPageDoesNotFetchStorefrontCategories() throws Exception {
        mvc.perform(get("/dashboard").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        verify(products, never()).getActiveCategories();
    }

    @Test
    void internalErrorKeepsItsPortalForAnonymousVisitors() throws Exception {
        mvc.perform(get("/error/403").requestAttr("internalPortalError", true))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-portal=\"internal\"")));
    }

    @Test
    void guestEntrypointsRemainPortalSpecific() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(redirectedUrl("/internal/login"));
        mvc.perform(get("/checkout")).andExpect(redirectedUrl("/auth/login"));
        mvc.perform(get("/api/receipts/1")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "MANAGER", "SALE_STAFF"})
    void staffMustUseInternalPortal(String role) throws Exception {
        mvc.perform(post("/auth/login").with(csrf()).param("username", role).param("password", "PortalExample391@"))
                .andExpect(redirectedUrl("/auth/login?error=portal"));
        var result = mvc.perform(post("/internal/login").with(csrf()).param("username", role)
                .param("password", "PortalExample391@")).andExpect(redirectedUrl("/dashboard")).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/dashboard").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-portal=\"internal\"")));
        mvc.perform(post("/internal/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl("/internal/login?logout"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void customerCannotAuthenticateThroughInternalPortal() throws Exception {
        var result = mvc.perform(post("/internal/login").with(csrf()).param("username", "CUSTOMER")
                .param("password", "PortalExample391@")).andExpect(redirectedUrl("/internal/login?error=portal")).andReturn();
        var session = result.getRequest().getSession(false);
        assertThat(session == null || session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null).isTrue();
        mvc.perform(post("/auth/login").with(csrf()).param("username", "CUSTOMER").param("password", "PortalExample391@"))
                .andExpect(redirectedUrl("/"));
    }

    @ParameterizedTest @ValueSource(strings = {"CUSTOMER", "ADMIN", "MANAGER", "SALE_STAFF"})
    void authenticatedUsersCannotRestartEntryFlows(String role) throws Exception {
        String target = role.equals("CUSTOMER") ? "/" : "/dashboard";
        for (String path : new String[]{"/auth/login", "/auth/register", "/internal/login", "/auth/google/login", "/oauth2/authorization/google", "/login/oauth2/code/google?code=old-flow&state=old-state"}) {
            mvc.perform(get(path).with(user("current").roles(role))).andExpect(redirectedUrl(target));
        }
        mvc.perform(post("/auth/register").with(user("current").roles(role)).with(csrf())).andExpect(redirectedUrl(target));
        mvc.perform(post("/auth/login").with(user("current").roles(role)).with(csrf())
                .param("username", "ADMIN").param("password", "PortalExample391@")).andExpect(redirectedUrl(target));
        verifyNoInteractions(otp);
        verify(users, never()).loadUserByUsername(anyString());
    }

    @Test
    void contextPathIsPreservedByEntryGuard() throws Exception {
        mvc.perform(get("/bookshop/auth/login").contextPath("/bookshop").with(user("current").roles("ADMIN")))
                .andExpect(redirectedUrl("/bookshop/dashboard"));
    }

    @Test
    void internalCsrfAndCrossRoleBoundariesRemainEnforced() throws Exception {
        mvc.perform(post("/internal/login").param("username", "ADMIN").param("password", "PortalExample391@"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/dashboard").with(user("customer").roles("CUSTOMER")).header("Accept", "application/json"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/checkout").with(user("admin").roles("ADMIN")).header("Accept", "application/json"))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalStaffCanChangePasswordAtInternalEndpoint() throws Exception {
        when(staffIdentity.resolve(any())).thenReturn(10L);
        when(accountSecurityService.changeStaffPassword(eq(10L), any(), any(), any()))
                .thenReturn(User.builder().id(10L).fullName("Staff").role(Role.builder().roleName("ROLE_SALE_STAFF").build()).build());

        mvc.perform(post("/internal/account/change-password")
                .with(user("staff").roles("SALE_STAFF"))
                .with(csrf())
                .param("currentPassword", "PortalExample391@")
                .param("newPassword", "NewStrongP@ss123")
                .param("confirmPassword", "NewStrongP@ss123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(accountSecurityService).changeStaffPassword(eq(10L), any(), any(), any());
        verify(sessionUpdater).refreshStaff(any(), any(), any());
    }

    @Test
    void customerCannotCallInternalPasswordChange() throws Exception {
        mvc.perform(post("/internal/account/change-password")
                .header("Accept", "application/json")
                .with(user("customer").roles("CUSTOMER"))
                .with(csrf())
                .param("currentPassword", "OldPass123!")
                .param("newPassword", "NewPass123!")
                .param("confirmPassword", "NewPass123!"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(accountSecurityService);
    }
}
