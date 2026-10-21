package com.ddagtech.aureliabooks.security;

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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real OAuth filter/token/userinfo wiring against a local fake provider; no Google credentials. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, CustomOAuth2UserService.class, LoginAttemptService.class, RoleBasedAuthenticationSuccessHandler.class})
@ActiveProfiles("google")
@TestPropertySource(properties = {
        "spring.security.oauth2.client.registration.google.client-id=qa-client",
        "spring.security.oauth2.client.registration.google.client-secret=qa-secret",
        "spring.security.oauth2.client.registration.google.scope=profile,email"})
class GoogleLinkCallbackTest {
    static final HttpServer provider = startProvider();
    @Autowired MockMvc mvc;
    @MockitoBean RegistrationService registration;
    @MockitoBean GoogleSignInService signIn;
    @MockitoBean GoogleAccountAction profileAction;
    @MockitoBean CustomUserDetailsService localUsers;

    static HttpServer startProvider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                byte[] body = "{\"access_token\":\"qa-access\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.createContext("/userinfo", exchange -> {
                byte[] body = "{\"sub\":\"qa-google-sub\",\"email\":\"member@example.com\",\"email_verified\":true,\"name\":\"Member\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            return server;
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }

    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry properties) {
        String base = "http://127.0.0.1:" + provider.getAddress().getPort();
        properties.add("spring.security.oauth2.client.provider.google.token-uri", () -> base + "/token");
        properties.add("spring.security.oauth2.client.provider.google.user-info-uri", () -> base + "/userinfo");
    }

    @AfterAll static void stop() { provider.stop(0); }

    @Test void collisionCallbackRedirectsToOtpWithoutCreatingAuthentication() throws Exception {
        when(signIn.resolve(any(), anyString(), anyString())).thenReturn(new GoogleSignInService.Outcome(null, "pending-proof", false));
        var start = mvc.perform(get("/oauth2/authorization/google")).andReturn();
        var session = (MockHttpSession) Objects.requireNonNull(start.getRequest().getSession(false));
        String state = UriComponentsBuilder.fromUriString(Objects.requireNonNull(start.getResponse().getRedirectedUrl()))
                .build().getQueryParams().getFirst("state");
        mvc.perform(get("/login/oauth2/code/google").session(session)
                        .param("state", java.net.URLDecoder.decode(state, StandardCharsets.UTF_8)).param("code", "qa-code"))
                .andExpect(redirectedUrl("/auth/google-link"));
        assertEquals("pending-proof", session.getAttribute("GOOGLE_LINK_FLOW"));
        assertNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
        verify(signIn).resolve(argThat(identity -> identity.subject().equals("qa-google-sub")
                && identity.email().equals("member@example.com") && identity.verified()), eq(session.getId()), anyString());
        verifyNoInteractions(registration);
    }

    @Test void invalidStateNeverResolvesOrLinksIdentity() throws Exception {
        mvc.perform(get("/login/oauth2/code/google").param("state", "invalid").param("code", "qa-code"))
                .andExpect(redirectedUrl("/auth/register"));
        verifyNoInteractions(signIn);
    }
}
