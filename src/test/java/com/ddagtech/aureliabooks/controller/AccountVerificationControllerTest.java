package com.ddagtech.aureliabooks.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.service.AccountOtpService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountVerificationController.class)
@Import(SecurityConfig.class)
class AccountVerificationControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountOtpService otp;

    @Test
    void forgotPasswordIsPublicAndAllPostsRequireCsrf() throws Exception {
        mvc.perform(get("/auth/forgot-password"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("name=\"_csrf\"")));
        for (String path : List.of(
            "/auth/forgot-password/lookup",
            "/auth/forgot-password/send",
            "/auth/verification/verify",
            "/auth/verification/resend",
            "/auth/verification/reset"
        ))
            mvc.perform(post(path)).andExpect(status().isForbidden());
        verifyNoInteractions(otp);
    }

    @Test
    void singleVerifiedChannelHasNoSelectorWhileTwoHaveChoice() throws Exception {
        when(otp.recoveryChannels("0912345678")).thenReturn(List.of("SMS"));
        when(otp.recovery(eq("0912345678"), eq("SMS"), anyString(), anyString())).thenReturn("flow");
        mvc.perform(post("/auth/forgot-password/lookup").with(csrf()).param("identifier", "0912345678")).andExpect(
            redirectedUrl("/auth/verification")
        );
        when(otp.recoveryChannels("0912345678")).thenReturn(List.of("EMAIL", "SMS"));
        mvc.perform(post("/auth/forgot-password/lookup").with(csrf()).param("identifier", "0912345678"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"recoveryChannel\"")));
    }

    @Test
    void unknownAccountStaysOnLookupWithErrorAndNoSend() throws Exception {
        var s = new MockHttpSession();
        s.setAttribute("RECOVERY_IDENTIFIER", "previous@example.test");
        when(otp.recoveryChannels("missing@example.test")).thenThrow(
            new IllegalArgumentException("Không tìm thấy tài khoản với email hoặc số điện thoại này.")
        );
        mvc.perform(
            post("/auth/forgot-password/lookup").session(s).with(csrf()).param("identifier", "missing@example.test")
        )
            .andExpect(status().isOk())
            .andExpect(view().name("auth/forgot-password"))
            .andExpect(content().string(containsString("Không tìm thấy tài khoản")))
            .andExpect(model().attributeDoesNotExist("channels"));
        org.assertj.core.api.Assertions.assertThat(s.getAttribute("RECOVERY_IDENTIFIER")).isNull();
        verify(otp, never()).recovery(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void deliveryFailureDoesNotRedirectToOtpOrReportSuccess() throws Exception {
        var s = new MockHttpSession();
        s.setAttribute("RECOVERY_IDENTIFIER", "a@example.test");
        when(otp.recovery(anyString(), anyString(), anyString(), anyString())).thenThrow(
            new IllegalArgumentException("Không gửi được mã. Vui lòng thử lại.")
        );
        mvc.perform(post("/auth/forgot-password/send").session(s).with(csrf()).param("channel", "EMAIL"))
            .andExpect(redirectedUrl("/auth/forgot-password"))
            .andExpect(flash().attribute("recoveryError", containsString("Không gửi được mã")))
            .andExpect(flash().attributeCount(3));
        org.assertj.core.api.Assertions.assertThat(s.getAttribute("ACCOUNT_OTP_FLOW")).isNull();
    }

    @Test
    void verificationUsesOnlySessionFlowAndCannotEchoSubmittedPassword() throws Exception {
        var s = new MockHttpSession();
        s.setAttribute("ACCOUNT_OTP_FLOW", "trusted-flow");
        when(otp.verify(eq("trusted-flow"), anyString(), eq("123456"), anyString())).thenReturn(
            new AccountOtpService.Result(false, "Verified")
        );
        mvc.perform(
            post("/auth/verification/verify")
                .session(s)
                .with(csrf())
                .param("flow", "attacker-flow")
                .param("code", "123456")
        )
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/auth/verification"));
        verify(otp).verify(eq("trusted-flow"), eq(s.getId()), eq("123456"), anyString());
    }

    @Test
    void resetWithoutVerifiedGrantShowsErrorWithoutInvokingLogin() throws Exception {
        var s = new MockHttpSession();
        s.setAttribute("ACCOUNT_OTP_FLOW", "flow");
        doThrow(new IllegalArgumentException("Vui lòng xác minh mã trước"))
            .when(otp)
            .reset(anyString(), anyString(), anyString(), anyString(), anyString());
        mvc.perform(
            post("/auth/verification/reset")
                .session(s)
                .with(csrf())
                .param("password", "NewPass1!")
                .param("confirmation", "NewPass1!")
        )
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/auth/verification"))
            .andExpect(flash().attribute("verificationMessage", "Vui lòng xác minh mã trước"));
    }

    @Test
    void countdownAndResetFormsExposeClientValidationHooks() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("ACCOUNT_OTP_FLOW", "flow");
        when(otp.view(eq("flow"), anyString())).thenReturn(
            new AccountOtpService.View("RESET", "a***@gmail.com", false, 3, 8)
        );
        var timer = mvc
            .perform(get("/auth/verification").session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-expires-seconds=\"8\"")))
            .andReturn();
        when(otp.view(eq("flow"), anyString())).thenReturn(
            new AccountOtpService.View("RESET", "a***@gmail.com", true, 0, 0)
        );
        var reset = mvc
            .perform(get("/auth/verification").session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-password-change")))
            .andReturn();
        String directory = System.getProperty("uc08.capture.dir");
        if (directory != null) {
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(directory, "account-timer.html"),
                timer.getResponse().getContentAsString()
            );
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(directory, "reset-password.html"),
                reset.getResponse().getContentAsString()
            );
        }
    }

    @Test
    void selectedChannelSendsDirectlyEvenWhenBothChannelsExist() throws Exception {
        when(otp.recoveryChannels("customer@example.test")).thenReturn(List.of("EMAIL", "SMS"));
        when(otp.recovery(eq("customer@example.test"), eq("EMAIL"), anyString(), anyString())).thenReturn("email-flow");
        mvc.perform(
            post("/auth/forgot-password/lookup")
                .with(csrf())
                .param("identifier", "customer@example.test")
                .param("channel", "EMAIL")
        ).andExpect(redirectedUrl("/auth/verification"));
        verify(otp).recovery(eq("customer@example.test"), eq("EMAIL"), anyString(), anyString());
    }

    @Test
    void selectedPhoneSendsOnlyToVerifiedPhone() throws Exception {
        when(otp.recoveryChannels("0912345678")).thenReturn(List.of("EMAIL", "SMS"));
        when(otp.recovery(eq("0912345678"), eq("SMS"), anyString(), anyString())).thenReturn("sms-flow");
        mvc.perform(
            post("/auth/forgot-password/lookup").with(csrf()).param("identifier", "0912345678").param("channel", "SMS")
        ).andExpect(redirectedUrl("/auth/verification"));
        verify(otp).recovery(eq("0912345678"), eq("SMS"), anyString(), anyString());
    }

    @Test
    void mismatchedInputAndUnknownChannelCannotSend() throws Exception {
        for (String channel : List.of("EMAIL", "INVALID")) {
            mvc.perform(
                post("/auth/forgot-password/lookup")
                    .with(csrf())
                    .param("identifier", "0912345678")
                    .param("channel", channel)
            )
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("recoveryError"));
        }
        verifyNoInteractions(otp);
    }

    @Test
    void unverifiedSelectedChannelDoesNotFallBackToOtherContact() throws Exception {
        when(otp.recoveryChannels("customer@example.test")).thenReturn(List.of("SMS"));
        mvc.perform(
            post("/auth/forgot-password/lookup")
                .with(csrf())
                .param("identifier", "customer@example.test")
                .param("channel", "EMAIL")
        )
            .andExpect(status().isOk())
            .andExpect(model().attributeExists("recoveryError"));
        verify(otp, never()).recovery(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void recoveryMethodRendersBeforeIdentifier() throws Exception {
        var result = mvc.perform(get("/auth/forgot-password")).andExpect(status().isOk()).andReturn();
        String html = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html.indexOf("id=\"recoveryMethod\"")).isLessThan(
            html.indexOf("id=\"recoveryIdentifier\"")
        );
        String directory = System.getProperty("uc08.capture.dir");
        if (directory != null) java.nio.file.Files.writeString(
            java.nio.file.Path.of(directory, "forgot-password.html"),
            html
        );
    }
}
