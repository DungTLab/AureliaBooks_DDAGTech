package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.*;
import com.ddagtech.aureliabooks.service.AccountOtpService;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GoogleLinkController.class)
@Import({SecurityConfig.class, GoogleLinkSessionAuthenticator.class, RoleBasedAuthenticationSuccessHandler.class,
        CustomAccessDeniedHandler.class})
class GoogleLinkControllerTest {
    @Autowired MockMvc mvc;
    @Autowired SessionRegistry registry;
    @MockitoBean AccountOtpService otp;
    @MockitoBean UserRepository users;
    MockHttpSession session;

    @BeforeEach void setup() {
        for (Object principal : registry.getAllPrincipals()) {
            for (var existing : registry.getAllSessions(principal, true)) registry.removeSessionInformation(existing.getSessionId());
        }
        session = new MockHttpSession();
        session.setAttribute("GOOGLE_LINK_FLOW", "bound-flow");
    }

    @Test void missingOrExpiredPendingProofReturnsToLoginWithoutSending() throws Exception {
        mvc.perform(get("/auth/google-link").session(session)).andExpect(redirectedUrl("/auth/login"));
        assertNull(session.getAttribute("GOOGLE_LINK_FLOW"));
        verify(otp, never()).sendGoogleLink(any(), any(), any(), any());
    }

    @Test void onlyVerifiedChannelsRenderAndSingleChannelHasNoSelect() throws Exception {
        when(otp.googleLinkView(any(), any())).thenReturn(new AccountOtpService.GoogleLinkView(List.of("SMS"), "", false, 0));
        var one = mvc.perform(get("/auth/google-link").session(session)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"googleLinkChannel\""))))
                .andExpect(content().string(containsString("name=\"_csrf\""))).andReturn();
        when(otp.googleLinkView(any(), any())).thenReturn(new AccountOtpService.GoogleLinkView(List.of("EMAIL", "SMS"), "m***@example.com", true, 59));
        var two = mvc.perform(get("/auth/google-link").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"googleLinkChannel\"")))
                .andExpect(content().string(containsString("id=\"googleLinkCode\""))).andReturn();
        String capture = System.getProperty("google.link.capture.dir");
        if (capture != null) {
            var directory = java.nio.file.Path.of(capture);
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve("single.html"), one.getResponse().getContentAsString());
            java.nio.file.Files.writeString(directory.resolve("otp.html"), two.getResponse().getContentAsString());
        }
        verify(otp, never()).sendGoogleLink(any(), any(), any(), any());
    }

    @Test void sendAndVerifyRequireCsrfAndIgnoreClientSuppliedIdentity() throws Exception {
        mvc.perform(post("/auth/google-link/send").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/auth/google-link/verify").session(session).param("code", "123456")).andExpect(status().isForbidden());
        verifyNoInteractions(otp);
        mvc.perform(post("/auth/google-link/send").session(session).with(csrf())
                .param("channel", "SMS").param("flow", "attacker-flow").param("owner", "999"))
                .andExpect(redirectedUrl("/auth/google-link"));
        verify(otp).sendGoogleLink(eq("bound-flow"), eq(session.getId()), eq("SMS"), anyString());
    }

    @Test void invalidCodeDoesNotAuthenticateOrLink() throws Exception {
        when(otp.verifyGoogleLink(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("Mã xác thực không đúng."));
        mvc.perform(post("/auth/google-link/verify").session(session).with(csrf()).param("code", "123456"))
                .andExpect(redirectedUrl("/auth/google-link"))
                .andExpect(flash().attribute("linkMessage", "Mã xác thực không đúng."));
        assertNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    @Test void successfulOtpRotatesSessionSavesContextRegistersSessionAndLandsCustomer() throws Exception {
        var owner = User.builder().id(7L).email("member@example.com").phone("0912345678").isActive(true)
                .role(Role.builder().roleName("ROLE_CUSTOMER").build()).build();
        when(otp.verifyGoogleLink(any(), any(), any(), any())).thenReturn(owner);
        String oldId = session.getId();
        mvc.perform(post("/auth/google-link/verify").session(session).with(csrf()).param("code", "123456"))
                .andExpect(redirectedUrl("/"));
        assertNotEquals(oldId, session.getId());
        assertNull(session.getAttribute("GOOGLE_LINK_FLOW"));
        var context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertNotNull(context);
        var principal = assertInstanceOf(CustomUserDetails.class, context.getAuthentication().getPrincipal());
        assertEquals(7L, principal.getId());
        assertTrue(context.getAuthentication().isAuthenticated());
        assertNotNull(registry.getSessionInformation(session.getId()));
    }

    @Test void otpLoginRespectsSessionLimitAndRestoresSavedCheckout() throws Exception {
        var owner = User.builder().id(8L).email("another@example.com").isActive(true)
                .role(Role.builder().roleName("ROLE_CUSTOMER").build()).build();
        var principal = new CustomUserDetails(owner);
        for (int i = 0; i < 5; i++) registry.registerNewSession("previous-" + i, principal);
        var saved = new org.springframework.mock.web.MockHttpServletRequest("GET", "/checkout");
        saved.setSession(session);
        new org.springframework.security.web.savedrequest.HttpSessionRequestCache()
                .saveRequest(saved, new org.springframework.mock.web.MockHttpServletResponse());
        when(otp.verifyGoogleLink(any(), any(), any(), any())).thenReturn(owner);
        mvc.perform(post("/auth/google-link/verify").session(session).with(csrf()).param("code", "123456"))
                .andExpect(redirectedUrl("http://localhost/checkout?continue"));
        assertEquals(5, registry.getAllSessions(principal, false).size());
        assertEquals(1, registry.getAllSessions(principal, true).stream().filter(information -> information.isExpired()).count());
    }
}
