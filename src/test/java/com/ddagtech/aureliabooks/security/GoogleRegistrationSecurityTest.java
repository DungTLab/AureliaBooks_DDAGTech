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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@org.springframework.test.context.ActiveProfiles("google")
@org.springframework.test.context.TestPropertySource(properties={"GOOGLE_CLIENT_ID=test-client","GOOGLE_CLIENT_SECRET=test-secret"})
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
                .andExpect(content().string(containsString("href=\"/oauth2/authorization/google\"")));
        mvc.perform(get("/oauth2/authorization/google")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",containsString("https://accounts.google.com/o/oauth2/v2/auth")))
                .andExpect(header().string("Location",containsString("state=")));
    }
    @Test void cancelledOAuthReturnsToRegistrationWithVietnameseFlash() throws Exception {
        var start=mvc.perform(get("/oauth2/authorization/google")).andReturn();
        String location=start.getResponse().getRedirectedUrl();
        String state=UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
        mvc.perform(get("/login/oauth2/code/google").session((MockHttpSession)start.getRequest().getSession())
                        .param("error","access_denied").param("state",state))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/register"));
        mvc.perform(get("/auth/register").session((MockHttpSession)start.getRequest().getSession()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Không thể đăng ký bằng Google. Vui lòng thử lại.")));
    }
}
