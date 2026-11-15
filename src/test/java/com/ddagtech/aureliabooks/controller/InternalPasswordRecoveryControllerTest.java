package com.ddagtech.aureliabooks.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.service.AccountOtpService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalPasswordRecoveryController.class)
@Import(SecurityConfig.class)
class InternalPasswordRecoveryControllerTest {

    private static final String FLOW = "INTERNAL_RECOVERY_FLOW";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountOtpService otp;

    @Test
    void verifyStepWithoutSessionRedirectsToStart() throws Exception {
        mvc.perform(get("/internal/forgot-password").param("step", "verify"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password"));

        verifyNoInteractions(otp);
    }

    @Test
    void visitingStartClearsPreviousRecoverySession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(FLOW, "");

        mvc.perform(get("/internal/forgot-password").session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("action=\"/internal/forgot-password/send\"")));

        assertThat(session.getAttribute(FLOW)).isNull();
    }

    @Test
    void missingNonBlankFlowRedirectsCleanlyToStartAndClearsSession() throws Exception {
        when(otp.internalRecovery(eq("staff@aureliabook.vn"), anyString(), anyString())).thenReturn("lost-flow");
        var sent = mvc.perform(post("/internal/forgot-password/send").with(csrf())
                .param("email", "staff@aureliabook.vn"))
            .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
            .andReturn();
        MockHttpSession session = (MockHttpSession) sent.getRequest().getSession(false);

        when(otp.view("lost-flow", session.getId(), true)).thenReturn(null);

        mvc.perform(get("/internal/forgot-password").param("step", "verify").session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password"))
            .andExpect(flash().attribute("recoveryMessage", containsString("hết hạn")));

        assertThat(session.getAttribute(FLOW)).isNull();
    }

    @Test
    void successfulVerifyFlashesPromptAndRendersHardenedResetForm() throws Exception {
        when(otp.internalRecovery(eq("staff@aureliabook.vn"), anyString(), anyString())).thenReturn("staff-flow");
        var sent = mvc.perform(post("/internal/forgot-password/send").with(csrf())
                .param("email", "staff@aureliabook.vn"))
            .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
            .andReturn();
        MockHttpSession session = (MockHttpSession) sent.getRequest().getSession(false);

        when(otp.verify("staff-flow", session.getId(), "123456", "127.0.0.1", true))
            .thenReturn(new AccountOtpService.Result(false, "Mã hợp lệ. Hãy đặt mật khẩu mới."));
        mvc.perform(post("/internal/forgot-password/verify").session(session).with(csrf()).param("code", "123456"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
            .andExpect(flash().attribute("recoveryMessage", "Mã hợp lệ. Hãy đặt mật khẩu mới."));

        when(otp.view("staff-flow", session.getId(), true))
            .thenReturn(new AccountOtpService.View("INTERNAL_RESET", "s***@aureliabook.vn", true, 55, 295));
        String html = mvc.perform(get("/internal/forgot-password").param("step", "verify").session(session))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("action=\"/internal/forgot-password/reset\"")
            .contains("data-valid-form", "data-password-change", "data-password-field", "data-password-toggle")
            .contains("disabled")
            .doesNotContain("id=\"internal-password-modal\"");
    }

    @Test
    void expiredResetGrantRestartsRecoveryFlowOnGetOrResetPost() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(FLOW, "staff-flow");
        session.setAttribute("INTERNAL_RECOVERY_AUTHORIZED", Boolean.TRUE);
        when(otp.view("staff-flow", session.getId(), true)).thenReturn(null);

        mvc.perform(post("/internal/forgot-password/reset").session(session).with(csrf())
                .param("password", "NewPass123!").param("confirmation", "NewPass123!"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password"))
            .andExpect(flash().attribute("recoveryMessage", containsString("hết hạn")));

        assertThat(session.getAttribute(FLOW)).isNull();
    }

    @Test
    void failedOtpAttemptsRestartSymmetricallyAfterFifthErrorWithoutLeakingEmail() throws Exception {
        when(otp.internalRecovery(eq("admin@aureliabook.vn"), anyString(), anyString()))
            .thenThrow(new IllegalArgumentException("Không thể gửi mã khôi phục."));
        var sent = mvc.perform(post("/internal/forgot-password/send").with(csrf())
                .param("email", "admin@aureliabook.vn"))
            .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
            .andReturn();
        MockHttpSession session = (MockHttpSession) sent.getRequest().getSession(false);

        doThrow(new IllegalArgumentException("Yêu cầu không còn hiệu lực."))
            .when(otp).verify(eq(""), eq(session.getId()), anyString(), anyString(), eq(true));

        for (int attempt = 1; attempt <= 4; attempt++) {
            mvc.perform(post("/internal/forgot-password/verify").session(session).with(csrf()).param("code", "000000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
                .andExpect(flash().attribute("recoveryMessage", "Mã không hợp lệ hoặc đã hết hạn."));
        }

        mvc.perform(post("/internal/forgot-password/verify").session(session).with(csrf()).param("code", "000000"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password"))
            .andExpect(flash().attribute("recoveryMessage", containsString("Vui lòng yêu cầu mã mới")));

        assertThat(session.getAttribute(FLOW)).isNull();
    }

    @Test
    void resendOutsideCooldownKeepsCountdownActiveWithoutLeakingFlowState() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(FLOW, "");
        session.setAttribute("INTERNAL_RECOVERY_RESEND_AT", Instant.now().plusSeconds(45));
        session.setAttribute("INTERNAL_RECOVERY_EXPIRES_AT", Instant.now().plusSeconds(250));

        mvc.perform(post("/internal/forgot-password/resend").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password?step=verify"));

        verify(otp).resend(eq(""), eq(session.getId()), anyString(), eq(true));
        mvc.perform(get("/internal/forgot-password").param("step", "verify").session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-otp-timer")))
            .andExpect(content().string(containsString("/js/otp-countdown.js")))
            .andExpect(content().string(not(containsString("admin@aureliabook.vn"))));
    }

    @Test
    void authenticatedInternalUserCannotAccessAnonymousRecoveryRoutes() throws Exception {
        mvc.perform(get("/internal/forgot-password").with(user("staff").roles("SALE_STAFF")))
            .andExpect(status().isForbidden());
        verifyNoInteractions(otp);
    }
}
