package com.ddagtech.aureliabooks.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.service.AccountOtpService;
import com.ddagtech.aureliabooks.service.AccountSecurityService;
import com.ddagtech.aureliabooks.service.ContactOtpDelivery;
import com.ddagtech.aureliabooks.service.impl.AccountOtpServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

@WebMvcTest(InternalPasswordRecoveryController.class)
@Import(SecurityConfig.class)
class InternalPasswordRecoveryControllerTest {

    private static final String FLOW = "INTERNAL_RECOVERY_FLOW";
    private static final String FLOW_AUTHORIZED = "INTERNAL_RECOVERY_AUTHORIZED";
    private static final String FLOW_EXPIRES_AT = "INTERNAL_RECOVERY_EXPIRES_AT";
    private static final String FLOW_RESEND_AT = "INTERNAL_RECOVERY_RESEND_AT";
    private static final String FLOW_ATTEMPTS = "INTERNAL_RECOVERY_ATTEMPTS";
    private static final String FLOW_SIMULATED_QUOTA_COUNT = "INTERNAL_RECOVERY_SIMULATED_QUOTA_COUNT";
    private static final String FLOW_SIMULATED_QUOTA_UNTIL = "INTERNAL_RECOVERY_SIMULATED_QUOTA_UNTIL";
    private static final Pattern EXPIRES_ATTR = Pattern.compile("data-expires-seconds=\"(\\d+)\"");
    private static final Pattern RESEND_ATTR = Pattern.compile("data-resend-seconds=\"(\\d+)\"");
    private static final Pattern RESEND_BUTTON_TAG = Pattern.compile("<button[^>]*data-otp-resend=\"true\"[^>]*>");
    private static final Pattern CSRF_INPUT = Pattern.compile("(<input[^>]*name=\"_csrf\"[^>]*value=\")[^\"]*(\"[^>]*>)");
    private static final Pattern CSRF_META = Pattern.compile("(<meta[^>]*name=\"_csrf\"[^>]*content=\")[^\"]*(\"[^>]*>)");
    private static final Pattern TIMER_VALUE = Pattern.compile("(data-(?:expires|resend)-seconds=\")\\d+(\")");
    private static final Pattern TIMER_NODE = Pattern.compile("(data-otp-(?:expiry|resend-seconds)[^>]*>)\\d+(<)");

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
    void unverifiedEvictedRealFlowAndDummyFlowRespondSymmetricallyUntilExpiry() throws Exception {
        when(otp.internalRecovery(eq("staff@aureliabook.vn"), anyString(), anyString())).thenReturn("lost-flow");
        when(otp.internalRecovery(eq("missing@aureliabook.vn"), anyString(), anyString()))
            .thenThrow(new IllegalArgumentException("Không thể gửi mã khôi phục."));

        MockHttpSession evictedSession = (MockHttpSession) mvc.perform(post("/internal/forgot-password/send")
                .with(csrf()).param("email", "staff@aureliabook.vn"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
            .andReturn().getRequest().getSession(false);

        MockHttpSession dummySession = (MockHttpSession) mvc.perform(post("/internal/forgot-password/send")
                .with(csrf()).param("email", "missing@aureliabook.vn"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
            .andReturn().getRequest().getSession(false);

        when(otp.view("lost-flow", evictedSession.getId(), true)).thenReturn(null);

        MvcResult evictedGet = mvc.perform(get("/internal/forgot-password").param("step", "verify").session(evictedSession))
            .andExpect(status().isOk())
            .andReturn();
        MvcResult dummyGet = mvc.perform(get("/internal/forgot-password").param("step", "verify").session(dummySession))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(evictedGet.getResponse().getContentAsString())
            .contains("action=\"/internal/forgot-password/verify\"", "data-otp-timer")
            .doesNotContain("staff@aureliabook.vn");
        assertThat(dummyGet.getResponse().getContentAsString())
            .contains("action=\"/internal/forgot-password/verify\"", "data-otp-timer")
            .doesNotContain("missing@aureliabook.vn");
        assertThat(extractSeconds(EXPIRES_ATTR, evictedGet.getResponse().getContentAsString()))
            .isCloseTo(extractSeconds(EXPIRES_ATTR, dummyGet.getResponse().getContentAsString()),
                org.assertj.core.data.Offset.offset(2L));

        mvc.perform(post("/internal/forgot-password/reset").session(evictedSession).with(csrf())
                .param("password", "NewPass123!").param("confirmation", "NewPass123!"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
            .andExpect(flash().attribute("recoveryMessage", "Vui lòng xác minh mã trước khi đặt mật khẩu."));
        mvc.perform(post("/internal/forgot-password/reset").session(dummySession).with(csrf())
                .param("password", "NewPass123!").param("confirmation", "NewPass123!"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
            .andExpect(flash().attribute("recoveryMessage", "Vui lòng xác minh mã trước khi đặt mật khẩu."));

        evictedSession.setAttribute(FLOW_EXPIRES_AT, Instant.now().minusSeconds(1));
        dummySession.setAttribute(FLOW_EXPIRES_AT, Instant.now().minusSeconds(1));

        mvc.perform(get("/internal/forgot-password").param("step", "verify").session(evictedSession))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password"))
            .andExpect(flash().attribute("recoveryMessage", containsString("hết hạn")));
        mvc.perform(get("/internal/forgot-password").param("step", "verify").session(dummySession))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password"))
            .andExpect(flash().attribute("recoveryMessage", containsString("hết hạn")));
        assertThat(evictedSession.getAttribute(FLOW)).isNull();
        assertThat(dummySession.getAttribute(FLOW)).isNull();
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
    void expiredResetGrantRestartsRecoveryFlowAcrossAllRecoveryEndpoints() throws Exception {
        for (String method : new String[] {"GET_VERIFY", "POST_RESEND", "POST_VERIFY", "POST_RESET"}) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute(FLOW, "staff-flow");
            session.setAttribute("INTERNAL_RECOVERY_AUTHORIZED", Boolean.TRUE);
            when(otp.view("staff-flow", session.getId(), true)).thenReturn(null);

            var action = switch (method) {
                case "GET_VERIFY" -> get("/internal/forgot-password").param("step", "verify").session(session);
                case "POST_RESEND" -> post("/internal/forgot-password/resend").session(session).with(csrf());
                case "POST_VERIFY" -> post("/internal/forgot-password/verify").session(session).with(csrf()).param("code", "123456");
                default -> post("/internal/forgot-password/reset").session(session).with(csrf())
                        .param("password", "NewPass123!").param("confirmation", "NewPass123!");
            };

            mvc.perform(action)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/forgot-password"))
                .andExpect(flash().attribute("recoveryMessage", containsString("hết hạn")));
            assertThat(session.getAttribute(FLOW)).isNull();
        }
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

    @Test
    void unverifiedResendProducesIdenticalPublicResponseAcrossRealSimulatedEvictedFailedAndRateLimitedStates()
            throws Exception {
        // Phase 1: Inside cooldown, all 5 unverified states preserve countdown, disabled button, and attempts.
        Instant baseNow = Instant.now();
        Instant initialExpires = baseNow.plusSeconds(240);
        Instant activeCooldown = baseNow.plusSeconds(28);
        Instant quotaUntil = baseNow.plusSeconds(850);

        MockHttpSession realSuccess = createUniformSession("real-ok", initialExpires, activeCooldown, 2, 1, quotaUntil);
        MockHttpSession realFail = createUniformSession("real-fail", initialExpires, activeCooldown, 2, 1, quotaUntil);
        MockHttpSession realLimit = createUniformSession("real-limit", initialExpires, activeCooldown, 2, 1, quotaUntil);
        MockHttpSession evicted = createUniformSession("evicted", initialExpires, activeCooldown, 2, 1, quotaUntil);
        MockHttpSession dummy = createUniformSession("", initialExpires, activeCooldown, 2, 1, quotaUntil);

        doAnswer(inv -> null).when(otp).resend(eq("real-ok"), eq(realSuccess.getId()), anyString(), eq(true));
        doThrow(new IllegalArgumentException("Không gửi được mã. Vui lòng kiểm tra cấu hình email/SMS."))
            .when(otp).resend(eq("real-fail"), eq(realFail.getId()), anyString(), eq(true));
        doThrow(new IllegalArgumentException("Vui lòng chờ đủ 60 giây."))
            .when(otp).resend(eq("real-limit"), eq(realLimit.getId()), anyString(), eq(true));
        doThrow(new IllegalArgumentException("Yêu cầu không còn hiệu lực."))
            .when(otp).resend(eq("evicted"), eq(evicted.getId()), anyString(), eq(true));
        doThrow(new IllegalArgumentException("Yêu cầu không còn hiệu lực."))
            .when(otp).resend(eq(""), eq(dummy.getId()), anyString(), eq(true));

        String phase1Template = null;
        for (MockHttpSession session : new MockHttpSession[] {realSuccess, realFail, realLimit, evicted, dummy}) {
            String html = performResendAndVerifyForm(session);
            assertSessionWindowCopy(html);
            assertThat(extractSeconds(EXPIRES_ATTR, html)).isBetween(237L, 240L);
            assertThat(extractSeconds(RESEND_ATTR, html)).isBetween(25L, 28L);
            assertThat(extractResendButtonTag(html)).contains("disabled=\"disabled\"");
            assertThat(session.getAttribute(FLOW_ATTEMPTS)).isEqualTo(2);
            String normalized = normalizePerSessionNoise(html);
            if (phase1Template == null) {
                phase1Template = normalized;
            } else {
                assertThat(normalized).isEqualTo(phase1Template);
            }
        }

        // Phase 2: Cooldown elapsed, all 5 unverified states return identical refreshed public timers (300/60) & reset attempts.
        Instant expiredCooldown = Instant.now().minusSeconds(1);
        Instant shorterRemaining = Instant.now().plusSeconds(180);
        realSuccess = createUniformSession("real-ok-2", shorterRemaining, expiredCooldown, 2, 1, quotaUntil);
        realFail = createUniformSession("real-fail-2", shorterRemaining, expiredCooldown, 2, 1, quotaUntil);
        realLimit = createUniformSession("real-limit-2", shorterRemaining, expiredCooldown, 2, 1, quotaUntil);
        evicted = createUniformSession("evicted-2", shorterRemaining, expiredCooldown, 2, 1, quotaUntil);
        dummy = createUniformSession("", shorterRemaining, expiredCooldown, 2, 1, quotaUntil);

        doAnswer(inv -> null).when(otp).resend(eq("real-ok-2"), eq(realSuccess.getId()), anyString(), eq(true));
        doThrow(new IllegalArgumentException("Không gửi được mã. Vui lòng kiểm tra cấu hình email/SMS."))
            .when(otp).resend(eq("real-fail-2"), eq(realFail.getId()), anyString(), eq(true));
        doThrow(new IllegalArgumentException("Quá nhiều yêu cầu. Vui lòng thử lại sau 15 phút."))
            .when(otp).resend(eq("real-limit-2"), eq(realLimit.getId()), anyString(), eq(true));
        doThrow(new IllegalArgumentException("Yêu cầu không còn hiệu lực."))
            .when(otp).resend(eq("evicted-2"), eq(evicted.getId()), anyString(), eq(true));
        doThrow(new IllegalArgumentException("Yêu cầu không còn hiệu lực."))
            .when(otp).resend(eq(""), eq(dummy.getId()), anyString(), eq(true));

        String phase2Template = null;
        for (MockHttpSession session : new MockHttpSession[] {realSuccess, realFail, realLimit, evicted, dummy}) {
            String html = performResendAndVerifyForm(session);
            assertSessionWindowCopy(html);
            assertThat(extractSeconds(EXPIRES_ATTR, html)).isBetween(297L, 300L);
            assertThat(extractSeconds(RESEND_ATTR, html)).isBetween(57L, 60L);
            assertThat(extractResendButtonTag(html)).contains("disabled=\"disabled\"");
            assertThat(session.getAttribute(FLOW_ATTEMPTS)).isEqualTo(0);
            String normalized = normalizePerSessionNoise(html);
            if (phase2Template == null) {
                phase2Template = normalized;
            } else {
                assertThat(normalized).isEqualTo(phase2Template);
            }
        }

        // Phase 3: Public 5-send quota exhausted keeps button locked symmetrically across real-rate-limit, evicted, dummy.
        Instant quotaLock = Instant.now().plusSeconds(720);
        realLimit = createUniformSession("real-quota", shorterRemaining, expiredCooldown, 2, 5, quotaLock);
        evicted = createUniformSession("evicted-quota", shorterRemaining, expiredCooldown, 2, 5, quotaLock);
        dummy = createUniformSession("", shorterRemaining, expiredCooldown, 2, 5, quotaLock);

        doThrow(new IllegalArgumentException("Quá nhiều yêu cầu. Vui lòng thử lại sau 15 phút."))
            .when(otp).resend(eq("real-quota"), eq(realLimit.getId()), anyString(), eq(true));
        doThrow(new IllegalArgumentException("Yêu cầu không còn hiệu lực."))
            .when(otp).resend(eq("evicted-quota"), eq(evicted.getId()), anyString(), eq(true));
        doThrow(new IllegalArgumentException("Yêu cầu không còn hiệu lực."))
            .when(otp).resend(eq(""), eq(dummy.getId()), anyString(), eq(true));

        String phase3Template = null;
        for (MockHttpSession session : new MockHttpSession[] {realLimit, evicted, dummy}) {
            String html = performResendAndVerifyForm(session);
            assertSessionWindowCopy(html);
            assertThat(extractSeconds(EXPIRES_ATTR, html)).isBetween(177L, 180L);
            assertThat(extractSeconds(RESEND_ATTR, html)).isBetween(717L, 720L);
            assertThat(extractResendButtonTag(html)).contains("disabled=\"disabled\"");
            assertThat(session.getAttribute(FLOW_ATTEMPTS)).isEqualTo(2);
            String normalized = normalizePerSessionNoise(html);
            if (phase3Template == null) {
                phase3Template = normalized;
            } else {
                assertThat(normalized).isEqualTo(phase3Template);
            }
        }
    }

    @Test
    void failedResendWithRealOtpServicePreservesOldOtpAndEnforcesBackendExpiryAndAttempts() throws Exception {
        User staff = User.builder()
            .id(42L)
            .email("staff@aureliabook.vn")
            .emailVerified(true)
            .passwordHash("old-hash")
            .role(Role.builder().roleName("ROLE_SALE_STAFF").build())
            .isActive(true)
            .build();
        UserRepository users = mock(UserRepository.class);
        when(users.findByIdentifierWithRoles(staff.getEmail())).thenReturn(Optional.of(staff));
        when(users.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(users.hasCurrentStaffEmailProof(staff)).thenReturn(true);

        ContactOtpDelivery delivery = mock(ContactOtpDelivery.class);
        AccountSecurityService writer = mock(AccountSecurityService.class);
        when(writer.resetStaff(eq(staff.getId()), eq("old-hash"), eq(staff.getEmail()), anyString(), anyString()))
            .thenReturn(staff);

        AtomicReference<String> sentCode = new AtomicReference<>();
        AtomicReference<Boolean> failNextSend = new AtomicReference<>(false);
        doAnswer(inv -> {
            if (Boolean.TRUE.equals(failNextSend.get())) {
                throw new MailSendException("SMTP transient failure to <staff@aureliabook.vn>");
            }
            sentCode.set(inv.getArgument(2));
            return null;
        }).when(delivery).send(eq("EMAIL"), eq(staff.getEmail()), anyString());

        try (ValidatorFactory vf = Validation.buildDefaultValidatorFactory()) {
            AccountOtpServiceImpl realOtp = new AccountOtpServiceImpl(
                users,
                vf.getValidator(),
                new BCryptPasswordEncoder(4),
                delivery,
                writer,
                new LoginAttemptService()
            );
            var viewResolver = new InternalResourceViewResolver("/templates/", ".html");
            MockMvc realMvc = MockMvcBuilders.standaloneSetup(new InternalPasswordRecoveryController(realOtp))
                .setViewResolvers(viewResolver)
                .build();

            // 1. After 2 wrong attempts & failed resend, public timer stays uniform while backend keeps old OTP + 2 failures.
            MockHttpSession session = (MockHttpSession) realMvc.perform(post("/internal/forgot-password/send")
                    .param("email", staff.getEmail()))
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
                .andReturn().getRequest().getSession(false);
            String firstCode = sentCode.get();
            String wrongCode = "000000".equals(firstCode) ? "111111" : "000000";
            for (int i = 0; i < 2; i++) {
                realMvc.perform(post("/internal/forgot-password/verify").session(session).param("code", wrongCode))
                    .andExpect(redirectedUrl("/internal/forgot-password?step=verify"));
            }

            String flowId = (String) session.getAttribute(FLOW);
            Object flow = mapField(realOtp, "flows").get(flowId);
            Object counter = mapField(realOtp, "limits").get("target:" + staff.getEmail());
            Instant earlierSent = Instant.now().minusSeconds(120);
            setDeclaredField(flow, "sent", earlierSent);
            setDeclaredField(counter, "lastSent", earlierSent);
            session.setAttribute(FLOW_RESEND_AT, earlierSent.plusSeconds(60));

            failNextSend.set(true);
            realMvc.perform(post("/internal/forgot-password/resend").session(session))
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"));

            MvcResult afterFailedResend = realMvc.perform(get("/internal/forgot-password")
                    .param("step", "verify").session(session))
                .andExpect(status().isOk())
                .andReturn();
            assertThat((Long) afterFailedResend.getModelAndView().getModel().get("otpExpiresSeconds"))
                .isBetween(297L, 300L);
            assertThat((Long) afterFailedResend.getModelAndView().getModel().get("otpResendSeconds"))
                .isBetween(57L, 60L);

            // Submitting 3 more wrong attempts exhausts the backend's 5-failure ceiling (2 + 3),
            // so even entering the valid old code afterward is rejected by the backend.
            for (int i = 0; i < 3; i++) {
                realMvc.perform(post("/internal/forgot-password/verify").session(session).param("code", wrongCode))
                    .andExpect(redirectedUrl("/internal/forgot-password?step=verify"));
            }
            realMvc.perform(post("/internal/forgot-password/verify").session(session).param("code", firstCode))
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
                .andExpect(flash().attribute("recoveryMessage", "Mã không hợp lệ hoặc đã hết hạn."));
            assertThat(session.getAttribute(FLOW_AUTHORIZED)).isNull();
            realMvc.perform(post("/internal/forgot-password/reset").session(session)
                    .param("password", "StaffNewPass1!").param("confirmation", "StaffNewPass1!"))
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"));
            verifyNoInteractions(writer);

            // 2. After failed resend extends public timer, valid old OTP works while still within backend TTL,
            //    and password reset succeeds when completed in time.
            failNextSend.set(false);
            setDeclaredField(counter, "lastSent", Instant.now().minusSeconds(61));
            MockHttpSession secondSession = (MockHttpSession) realMvc.perform(post("/internal/forgot-password/send")
                    .param("email", staff.getEmail()))
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
                .andReturn().getRequest().getSession(false);
            String validOldCode = sentCode.get();
            String flowId2 = (String) secondSession.getAttribute(FLOW);
            Object flow2 = mapField(realOtp, "flows").get(flowId2);
            Instant oldSent2 = Instant.now().minusSeconds(120);
            setDeclaredField(flow2, "sent", oldSent2);
            setDeclaredField(flow2, "expires", oldSent2.plusSeconds(300));
            setDeclaredField(counter, "lastSent", oldSent2);
            secondSession.setAttribute(FLOW_RESEND_AT, oldSent2.plusSeconds(60));

            failNextSend.set(true);
            realMvc.perform(post("/internal/forgot-password/resend").session(secondSession))
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"));

            realMvc.perform(post("/internal/forgot-password/verify").session(secondSession).param("code", validOldCode))
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
                .andExpect(flash().attribute("recoveryMessage", "Mã hợp lệ. Hãy đặt mật khẩu mới."));
            realMvc.perform(post("/internal/forgot-password/reset").session(secondSession)
                    .param("password", "StaffNewPass1!")
                    .param("confirmation", "StaffNewPass1!"))
                .andExpect(redirectedUrl("/internal/login"));
            verify(writer, times(1)).resetStaff(eq(staff.getId()), eq("old-hash"), eq(staff.getEmail()), anyString(), anyString());

            // 3. Even if public timer was extended after failed resend, backend still rejects an expired old OTP.
            failNextSend.set(false);
            setDeclaredField(counter, "lastSent", Instant.now().minusSeconds(61));
            MockHttpSession thirdSession = (MockHttpSession) realMvc.perform(post("/internal/forgot-password/send")
                    .param("email", staff.getEmail()))
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
                .andReturn().getRequest().getSession(false);
            String expiredOldCode = sentCode.get();
            String flowId3 = (String) thirdSession.getAttribute(FLOW);
            Object flow3 = mapField(realOtp, "flows").get(flowId3);
            Instant oldSent3 = Instant.now().minusSeconds(120);
            setDeclaredField(flow3, "sent", oldSent3);
            setDeclaredField(flow3, "expires", Instant.now().plusSeconds(10));
            setDeclaredField(counter, "lastSent", oldSent3);
            thirdSession.setAttribute(FLOW_RESEND_AT, oldSent3.plusSeconds(60));

            failNextSend.set(true);
            realMvc.perform(post("/internal/forgot-password/resend").session(thirdSession))
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"));

            // Backend OTP now expires while public session timer still appears active.
            setDeclaredField(flow3, "expires", Instant.now().minusSeconds(1));
            String renderedVerifyHtml = mvc.perform(get("/internal/forgot-password")
                    .param("step", "verify").session(thirdSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            assertSessionWindowCopy(renderedVerifyHtml);
            assertThat(extractSeconds(EXPIRES_ATTR, renderedVerifyHtml)).isBetween(297L, 300L);
            assertThat(extractSeconds(RESEND_ATTR, renderedVerifyHtml)).isBetween(57L, 60L);
            assertThat(extractResendButtonTag(renderedVerifyHtml)).contains("disabled=\"disabled\"");

            realMvc.perform(post("/internal/forgot-password/verify").session(thirdSession).param("code", expiredOldCode))
                .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
                .andExpect(flash().attribute("recoveryMessage", "Mã không hợp lệ hoặc đã hết hạn."));
            assertThat(thirdSession.getAttribute(FLOW_AUTHORIZED)).isNull();
            assertThatThrownBy(() -> realOtp.verify(flowId3, thirdSession.getId(), expiredOldCode, "127.0.0.1", true))
                .hasMessageContaining("hết hạn");
            verify(writer, times(1)).resetStaff(any(), anyString(), anyString(), anyString(), anyString());
        }
    }

    private static void assertSessionWindowCopy(String html) {
        assertThat(html)
            .contains("Thời gian còn lại của phiên nhập mã")
            .contains("Gửi lại mã")
            .contains("Yêu cầu mã mới hoặc dùng email khác")
            .contains("href=\"/internal/forgot-password\"")
            .doesNotContain("Mã có hiệu lực");
    }

    private static String normalizePerSessionNoise(String html) {
        String scrubbed = CSRF_INPUT.matcher(html).replaceAll("$1CSRF$2");
        scrubbed = CSRF_META.matcher(scrubbed).replaceAll("$1CSRF$2");
        scrubbed = TIMER_VALUE.matcher(scrubbed).replaceAll("$1000$2");
        return TIMER_NODE.matcher(scrubbed).replaceAll("$1000$2");
    }

    private MockHttpSession createUniformSession(
            String flowId,
            Instant expiresAt,
            Instant resendAt,
            int attempts,
            int quotaCount,
            Instant quotaUntil
    ) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(FLOW, flowId);
        session.setAttribute(FLOW_EXPIRES_AT, expiresAt);
        session.setAttribute(FLOW_RESEND_AT, resendAt);
        session.setAttribute(FLOW_ATTEMPTS, attempts);
        session.setAttribute(FLOW_SIMULATED_QUOTA_COUNT, quotaCount);
        session.setAttribute(FLOW_SIMULATED_QUOTA_UNTIL, quotaUntil);
        return session;
    }

    private String performResendAndVerifyForm(MockHttpSession session) throws Exception {
        mvc.perform(post("/internal/forgot-password/resend").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/internal/forgot-password?step=verify"))
            .andExpect(flash().attribute("recoveryMessage",
                    "Nếu email đủ điều kiện khôi phục, bạn sẽ nhận được mã xác thực."));
        return mvc.perform(get("/internal/forgot-password").param("step", "verify").session(session))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private static long extractSeconds(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        assertThat(matcher.find()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static String extractResendButtonTag(String html) {
        Matcher matcher = RESEND_BUTTON_TAG.matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> mapField(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, ?>) field.get(target);
    }

    private static void setDeclaredField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
