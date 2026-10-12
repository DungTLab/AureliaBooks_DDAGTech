package com.ddagtech.aureliabooks.security;
import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.AuthController;
import com.ddagtech.aureliabooks.service.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.mock.web.MockHttpSession;
import java.util.Objects;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@org.springframework.test.context.ActiveProfiles("google")
@org.springframework.test.context.TestPropertySource(properties={
        "spring.security.oauth2.client.registration.google.client-id=test-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret"})
class GoogleRegistrationSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.security.oauth2.client.registration.ClientRegistrationRepository clients;
    @MockitoBean RegistrationService registration;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @Test void googleButtonAndAuthorizationRedirectWorkWhenConfigured() throws Exception {
        var google = clients.findByRegistrationId("google");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("openid", "profile", "email"), google.getScopes());
        org.junit.jupiter.api.Assertions.assertEquals("sub", google.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
        mvc.perform(get("/auth/register")).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/auth/google/register\"")));
        mvc.perform(get("/oauth2/authorization/google")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",containsString("https://accounts.google.com/o/oauth2/v2/auth")))
                .andExpect(header().string("Location",containsString("state=")));
    }
    @Test void cancelledOAuthReturnsToRegistrationWithVietnameseFlash() throws Exception {
        var start=mvc.perform(get("/oauth2/authorization/google")).andReturn();
        String location=Objects.requireNonNull(start.getResponse().getRedirectedUrl(), "OAuth redirect missing");
        String state=Objects.requireNonNull(UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state"), "OAuth state missing");
        var session=(MockHttpSession)Objects.requireNonNull(start.getRequest().getSession(false), "OAuth session missing");
        mvc.perform(get("/login/oauth2/code/google").session(session)
                        .param("error","access_denied").param("state",state))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/register"));
        mvc.perform(get("/auth/register").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Không thể xác thực bằng Google. Vui lòng thử lại.")));
    }

    @Test void cancelledLoginOAuthReturnsToLoginAndRegistrationOverridesOldOrigin() throws Exception {
        var entry=mvc.perform(get("/auth/google/login")).andExpect(redirectedUrl("/oauth2/authorization/google")).andReturn();
        var session=(MockHttpSession)Objects.requireNonNull(entry.getRequest().getSession(false), "OAuth entry session missing");
        var start=mvc.perform(get("/oauth2/authorization/google").session(session)).andReturn();
        String location=Objects.requireNonNull(start.getResponse().getRedirectedUrl(), "OAuth redirect missing");
        String state=Objects.requireNonNull(UriComponentsBuilder.fromUriString(location)
                .build().getQueryParams().getFirst("state"), "OAuth state missing");
        mvc.perform(get("/login/oauth2/code/google").session(session).param("error","access_denied").param("state",state))
                .andExpect(redirectedUrl("/auth/login"));
        mvc.perform(get("/auth/login").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Không thể xác thực bằng Google")))
                .andExpect(content().string(containsString("href=\"/auth/google/login\"")));
        mvc.perform(get("/auth/google/login").session(session));
        mvc.perform(get("/auth/google/register").session(session)).andExpect(redirectedUrl("/oauth2/authorization/google"));
        org.junit.jupiter.api.Assertions.assertEquals("register",session.getAttribute("GOOGLE_AUTH_ORIGIN"));
    }
}
