package com.ddagtech.aureliabooks.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.service.AccountOtpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class RegistrationUxTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountOtpService otp;

    String header(String html) {
        return html.substring(html.indexOf("<header"), html.indexOf("</header>"));
    }

    @Test
    void registrationNavbarAvoidsCurrentPageAndFormIncludesAccessibleStateFeedback() throws Exception {
        String html = mvc
            .perform(get("/auth/register"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertFalse(header(html).contains("href=\"/auth/register\""));
        assertTrue(header(html).contains("href=\"/auth/login\""));
        assertTrue(html.contains("id=\"registrationHint\""));
        assertTrue(html.contains("aria-describedby=\"registrationHint\""));
        assertTrue(html.contains("/js/registration-form.js"));
    }

    @Test
    void loginNavbarKeepsRegistrationAndHidesRedundantLogin() throws Exception {
        String html = mvc
            .perform(get("/auth/login"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertTrue(header(html).contains("href=\"/auth/register\""));
        assertFalse(header(html).contains("href=\"/auth/login\""));
    }

    @Test
    void bypassingDisabledButtonCannotBypassServerTermsOrPasswordValidation() throws Exception {
        var result = mvc
            .perform(
                post("/auth/register")
                    .with(csrf())
                    .param("fullName", "Nguyễn An")
                    .param("phone", "0912345678")
                    .param("password", "short")
                    .param("confirmPassword", "short")
                    .param("agreeTerms", "false")
            )
            .andExpect(status().isOk())
            .andExpect(model().attributeHasFieldErrors("registerRequest", "agreeTerms", "password"))
            .andReturn();
        assertFalse(header(result.getResponse().getContentAsString()).contains("href=\"/auth/register\""));
        verifyNoInteractions(otp);
    }
}
