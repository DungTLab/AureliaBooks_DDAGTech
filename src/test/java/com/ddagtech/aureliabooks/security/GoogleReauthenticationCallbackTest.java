package com.ddagtech.aureliabooks.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.AuthController;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.service.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

/** Real OAuth filter/token/userinfo wiring against a local fake provider; no Google credentials. */
@WebMvcTest({
    AuthController.class,
    com.ddagtech.aureliabooks.controller.GoogleProfileController.class,
    com.ddagtech.aureliabooks.controller.ContactChangeController.class,
})
@Import({
    SecurityConfig.class,
    CustomOAuth2UserService.class,
    LoginAttemptService.class,
    RoleBasedAuthenticationSuccessHandler.class,
    GoogleAccountAction.class,
})
@ActiveProfiles("google")
@TestPropertySource(
    properties = {
        "spring.security.oauth2.client.registration.google.client-id=qa-client",
        "spring.security.oauth2.client.registration.google.client-secret=qa-secret",
        "spring.security.oauth2.client.registration.google.scope=profile,email",
    }
)
class GoogleReauthenticationCallbackTest {

    static final HttpServer provider = startProvider();

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RegistrationService registration;

    @MockitoBean
    GoogleSignInService signIn;

    @MockitoBean
    AccountSecurityService accountSecurity;

    @MockitoBean
    CustomerIdentity customerIdentity;

    @MockitoBean
    ContactChangeService contactChanges;

    @MockitoBean
    AuthenticatedSessionUpdater sessionUpdater;

    @MockitoBean
    CustomUserDetailsService localUsers;

    static HttpServer startProvider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                byte[] body = "{\"access_token\":\"qa-access\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(
                    StandardCharsets.UTF_8
                );
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/userinfo", exchange -> {
                byte[] body =
                    "{\"sub\":\"qa-google-sub\",\"email\":\"member@example.com\",\"email_verified\":true,\"name\":\"Member\"}".getBytes(
                        StandardCharsets.UTF_8
                    );
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry properties) {
        String base = "http://127.0.0.1:" + provider.getAddress().getPort();
        properties.add("spring.security.oauth2.client.provider.google.token-uri", () -> base + "/token");
        properties.add("spring.security.oauth2.client.provider.google.user-info-uri", () -> base + "/userinfo");
    }

    @AfterAll
    static void stop() {
        provider.stop(0);
    }

    private MockHttpSession begin(String type, String subject) throws Exception {
        when(customerIdentity.resolve(any())).thenReturn(7L);
        when(accountSecurity.contactSettings(7L)).thenReturn(
            new com.ddagtech.aureliabooks.dto.response.ContactSettingsView(false, true, true)
        );
        var owner = User.builder()
            .id(7L)
            .providerId(subject)
            .isActive(true)
            .role(Role.builder().roleName("ROLE_CUSTOMER").build())
            .build();
        when(accountSecurity.customer(7L)).thenReturn(owner);
        var session = new MockHttpSession();
        mvc.perform(
            post("/profile/google/reauth")
                .session(session)
                .with(
                    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
                        "original-customer"
                    ).roles("CUSTOMER")
                )
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("type", type)
        ).andExpect(redirectedUrl("/oauth2/authorization/google"));
        return session;
    }

    private String state(MockHttpSession session) throws Exception {
        var start = mvc.perform(get("/oauth2/authorization/google").session(session)).andReturn();
        String state = UriComponentsBuilder.fromUriString(
            Objects.requireNonNull(start.getResponse().getRedirectedUrl())
        )
            .build()
            .getQueryParams()
            .getFirst("state");
        return java.net.URLDecoder.decode(state, StandardCharsets.UTF_8);
    }

    @Test
    void wrongGooglePreservesLoginAndPhoneReturnPath() throws Exception {
        wrongGooglePreservesLogin("PHONE");
    }

    @Test
    void wrongGooglePreservesLoginAndEmailReturnPath() throws Exception {
        wrongGooglePreservesLogin("EMAIL");
    }

    private void wrongGooglePreservesLogin(String type) throws Exception {
        var session = begin(type, "original-sub");
        mvc.perform(
            get("/login/oauth2/code/google").session(session).param("state", state(session)).param("code", "qa-code")
        )
            .andExpect(redirectedUrl("/profile/contact-change?type=" + type))
            .andExpect(
                org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated().withUsername(
                    "original-customer"
                )
            );
        assertFalse(session.isInvalid());
        assertNull(session.getAttribute("GOOGLE_REAUTH_OWNER"));
        assertNull(session.getAttribute("GOOGLE_REAUTH_ORIGINAL_AUTH"));
        verifyNoInteractions(signIn, registration);
        mvc.perform(get("/profile/contact-change").param("type", type).session(session))
            .andExpect(status().isOk())
            .andExpect(model().attribute("reauthenticated", false))
            .andExpect(
                org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated().withUsername(
                    "original-customer"
                )
            );
        assertNotNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
    }

    @Test
    void matchingGoogleCreatesPhoneScopedProofAndReturnsToPhone() throws Exception {
        successfulReauthentication("PHONE");
    }

    @Test
    void matchingGoogleCreatesEmailScopedProofAndReturnsToEmail() throws Exception {
        successfulReauthentication("EMAIL");
    }

    private void successfulReauthentication(String type) throws Exception {
        var session = begin(type, "qa-google-sub");
        var callback = mvc
            .perform(
                get("/login/oauth2/code/google")
                    .session(session)
                    .param("state", state(session))
                    .param("code", "qa-code")
            )
            .andExpect(redirectedUrl("/profile/contact-change?type=" + type))
            .andExpect(
                org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated()
            )
            .andReturn();
        session = (MockHttpSession) callback.getRequest().getSession(false);
        assertEquals(7L, session.getAttribute("CONTACT_REAUTH_OWNER"));
        assertEquals(type, session.getAttribute("CONTACT_REAUTH_TYPE"));
        mvc.perform(get("/profile/contact-change").param("type", type).session(session))
            .andExpect(status().isOk())
            .andExpect(model().attribute("reauthenticated", true))
            .andExpect(model().attribute("selectedType", type));
        assertNull(session.getAttribute("GOOGLE_REAUTH_ORIGINAL_AUTH"));
        verifyNoInteractions(signIn, registration);
    }

    @Test
    void cancelledGoogleKeepsOriginalSessionWithoutGrant() throws Exception {
        var session = begin("PHONE", "original-sub");
        mvc.perform(
            get("/login/oauth2/code/google")
                .session(session)
                .param("state", state(session))
                .param("error", "access_denied")
        )
            .andExpect(redirectedUrl("/profile/contact-change?type=PHONE"))
            .andExpect(
                org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated().withUsername(
                    "original-customer"
                )
            );
        assertNull(session.getAttribute("CONTACT_REAUTH_OWNER"));
        verifyNoInteractions(signIn, registration, accountSecurity);
    }

    @Test
    void expiredGoogleIntentNeverCreatesGrantOrSwitchesAccount() throws Exception {
        var session = begin("EMAIL", "qa-google-sub");
        session.setAttribute(
            "GOOGLE_ACCOUNT_ACTION",
            new GoogleAccountAction.Intent(7L, "REAUTH", java.time.Instant.now().minusSeconds(1))
        );
        mvc.perform(
            get("/login/oauth2/code/google").session(session).param("state", state(session)).param("code", "qa-code")
        )
            .andExpect(redirectedUrl("/profile/contact-change?type=EMAIL"))
            .andExpect(
                org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated().withUsername(
                    "original-customer"
                )
            );
        assertNull(session.getAttribute("CONTACT_REAUTH_OWNER"));
        verifyNoInteractions(signIn, registration);
    }
}
