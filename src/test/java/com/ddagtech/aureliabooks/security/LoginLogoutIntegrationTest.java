package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.AuthController;
import com.ddagtech.aureliabooks.controller.HomeController;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.RegistrationService;
import com.ddagtech.aureliabooks.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuthController.class, HomeController.class, LoginLogoutIntegrationTest.ProtectedPage.class})
@Import({SecurityConfig.class, CustomUserDetailsService.class, RoleBasedAuthenticationSuccessHandler.class,
        CustomAuthenticationFailureHandler.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        LoginAttemptService.class, LogoutAuditHandler.class, LoginLogoutIntegrationTest.ProtectedPage.class,
        com.ddagtech.aureliabooks.controller.admin.DashboardController.class})
class LoginLogoutIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserRepository users;
    @MockitoBean RegistrationService registration;
    @MockitoBean AuditLogService audit;
    private User account;

    @BeforeEach void setup() {
        account = User.builder().id(600L).email("member@example.com").phone("0901234567")
                .fullName("Thành Viên").passwordHash(new BCryptPasswordEncoder(4).encode("Strong@123"))
                .role(Role.builder().roleName("ROLE_CUSTOMER").build()).isActive(true).build();
        when(users.findByIdentifierWithRoles(anyString())).thenAnswer(invocation -> {
            String identifier = invocation.getArgument(0);
            return identifier.equals(account.getEmail()) || identifier.equals(account.getPhone())
                    ? Optional.of(account) : Optional.empty();
        });
    }

    @Test void loginPageContainsVietnameseFormAndCsrf() throws Exception {
        var page=mvc.perform(get("/auth/login")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Email hoặc số điện thoại")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\""))).andReturn();
        String captureDirectory=System.getProperty("uc06.capture.dir");
        if (captureDirectory != null) {
            var directory=java.nio.file.Path.of(captureDirectory);
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve("login.html"),page.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            var error=mvc.perform(get("/auth/login").param("error","throttled"))
                    .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("15 phút"))).andReturn();
            java.nio.file.Files.writeString(directory.resolve("login-throttled.html"),error.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void fifthFailureLocksTheAccountAcrossEmailAndPhone() throws Exception {
        account.setId(601L);
        for (int i=0;i<4;i++) login(i%2==0 ? account.getEmail() : account.getPhone(), "wrong")
                .andExpect(redirectedUrl("/auth/login?error=credentials"));
        login(account.getEmail(), "wrong").andExpect(redirectedUrl("/auth/login?error=throttled"));
        login(account.getPhone(), "Strong@123").andExpect(redirectedUrl("/auth/login?error=throttled"));
    }

    @Test void localLoginAndLogoutInvalidateTheSessionAndReturnHome() throws Exception {
        var result=login(account.getPhone(), "Strong@123").andExpect(redirectedUrl("/" )).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(get("/account/test").session(session)).andExpect(status().isOk());
        mvc.perform(post("/auth/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl("/"))
                .andExpect(cookie().maxAge("JSESSIONID",0));
        assertTrue(session.isInvalid());
        verify(audit).record(eq(600L), eq("LOGOUT"), eq("users"), eq(600L),
                eq("{\"method\":\"LOCAL\"}"), anyString());
        mvc.perform(get("/account/test").cookie(new jakarta.servlet.http.Cookie("JSESSIONID",session.getId())))
                .andExpect(redirectedUrl("/auth/login"));
        assertNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void inactiveAndUnknownAccountsAreRejected() throws Exception {
        account.setIsActive(false);
        login(account.getEmail(),"Strong@123").andExpect(redirectedUrl("/auth/login?error=locked"));
        login("missing@example.com","Strong@123").andExpect(redirectedUrl("/auth/login?error=credentials"));
    }

    @Test void loginWithoutCsrfDoesNotAuthenticate() throws Exception {
        mvc.perform(post("/auth/login").param("username",account.getEmail()).param("password","Strong@123"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ROLE_CUSTOMER","ROLE_SALE_STAFF","ROLE_MANAGER","ROLE_ADMIN"})
    void oneRoleControlsLandingAndSessionAuthorities(String role) throws Exception {
        account.setId(700L + role.hashCode());
        account.setRole(Role.builder().roleName(role).build());
        var result=login(" MEMBER@EXAMPLE.COM ","Strong@123")
                .andExpect(redirectedUrl(role.equals("ROLE_CUSTOMER") ? "/" : "/dashboard")).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        var context=(org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertEquals(1,context.getAuthentication().getAuthorities().size());
        assertEquals(role,context.getAuthentication().getAuthorities().iterator().next().getAuthority());
        if (!role.equals("ROLE_CUSTOMER")) mvc.perform(get("/dashboard").session(session))
                .andExpect(status().isOk()).andExpect(view().name("admin/dashboard"));
    }

    @Test void unsupportedOrMissingRoleCannotLogin() throws Exception {
        account.setRole(null);
        login(account.getEmail(),"Strong@123").andExpect(redirectedUrl("/auth/login?error=disabled"));
        account.setRole(Role.builder().roleName("ROLE_UNKNOWN").build());
        login(account.getEmail(),"Strong@123").andExpect(redirectedUrl("/auth/login?error=disabled"));
    }

    @Test void successfulLoginResetsTheFailureChain() throws Exception {
        account.setId(602L);
        for (int i=0;i<4;i++) login(account.getEmail(),"wrong").andExpect(redirectedUrl("/auth/login?error=credentials"));
        login(account.getEmail(),"Strong@123").andExpect(redirectedUrl("/"));
        for (int i=0;i<4;i++) login(account.getPhone(),"wrong").andExpect(redirectedUrl("/auth/login?error=credentials"));
        login(account.getPhone(),"Strong@123").andExpect(redirectedUrl("/"));
    }

    @Test void logoutWithoutCsrfIsDeniedAndAuditFailureCannotKeepSessionAlive() throws Exception {
        account.setId(603L);
        var result=login(account.getEmail(),"Strong@123").andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        mvc.perform(post("/auth/logout").session(session)).andExpect(status().isForbidden());
        assertFalse(session.isInvalid());
        verifyNoInteractions(audit);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(),any(),any(),any(),any(),any());
        mvc.perform(post("/auth/logout").session(session).with(csrf())).andExpect(redirectedUrl("/"));
        assertTrue(session.isInvalid());
    }

    @Test void loginMigratesTheAnonymousSession() throws Exception {
        account.setId(604L);
        var previous=new MockHttpSession();
        var result=mvc.perform(post("/auth/login").session(previous).with(csrf())
                .param("username",account.getEmail()).param("password","Strong@123"))
                .andExpect(redirectedUrl("/")).andReturn();
        assertTrue(previous.isInvalid());
        assertNotEquals(previous.getId(),result.getRequest().getSession(false).getId());
    }

    @Test void googleLogoutUsesPersistentIdentityForAudit() throws Exception {
        account.setId(650L);
        when(users.findByGoogleIdentity(User.AuthProvider.GOOGLE,"subject-650")).thenReturn(Optional.of(account));
        var session=new MockHttpSession();
        mvc.perform(post("/auth/logout").session(session).with(csrf())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login()
                        .attributes(attributes -> attributes.put("sub","subject-650"))))
                .andExpect(redirectedUrl("/")).andExpect(cookie().maxAge("JSESSIONID",0));
        assertTrue(session.isInvalid());
        verify(audit).record(eq(650L),eq("LOGOUT"),eq("users"),eq(650L),eq("{\"method\":\"GOOGLE\"}"),anyString());
    }

    @Test void googleOnlyAccountCannotAuthenticateWithAnInventedLocalPassword() throws Exception {
        account.setId(651L);
        account.setPasswordHash(null);
        account.setAuthProvider(User.AuthProvider.GOOGLE);
        login(account.getEmail(),"Strong@123").andExpect(redirectedUrl("/auth/login?error=credentials"));
    }

    @Test void oversizedIncorrectPasswordsReturnACredentialError() throws Exception {
        account.setId(652L);
        login(account.getEmail(),"x".repeat(100)).andExpect(redirectedUrl("/auth/login?error=credentials"));
        login("missing@example.com","x".repeat(100)).andExpect(redirectedUrl("/auth/login?error=credentials"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String identifier,String password) throws Exception {
        return mvc.perform(post("/auth/login").with(csrf()).param("username",identifier).param("password",password));
    }

    @RestController static class ProtectedPage {
        @GetMapping("/account/test") String protectedPage() { return "protected"; }
    }
}
