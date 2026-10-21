package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.service.ContactOtpDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DemoSmsOtpDeliveryTest {
    private JavaMailSender mail;
    private StaticListableBeanFactory beans;

    @BeforeEach
    void setUp() {
        mail = mock(JavaMailSender.class);
        beans = new StaticListableBeanFactory();
        beans.addBean("mail", mail);
    }

    private DemoSmsOtpDelivery delivery() {
        return new DemoSmsOtpDelivery(beans.getBeanProvider(JavaMailSender.class), new MockEnvironment(),
                "sender@example.test", "team@example.test");
    }

    @Test
    void demoSmsOnlyGoesToPrivateTeamInboxAndIsClearlyLabelled() {
        delivery().send("SMS", "0912345678", "135790");
        var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(capture.capture());
        var message = capture.getValue();
        assertThat(message.getTo()).containsExactly("team@example.test");
        assertThat(message.getSubject()).contains("[SMS DEMO]");
        assertThat(message.getText()).contains("135790", "******5678", "Không xác minh")
                .doesNotContain("0912345678");
    }

    @Test
    void emailVerificationStillGoesToActualRecipient() {
        delivery().send("EMAIL", "customer@example.test", "246810");
        var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(capture.capture());
        assertThat(capture.getValue().getTo()).containsExactly("customer@example.test");
        assertThat(capture.getValue().getSubject()).doesNotContain("SMS DEMO");
    }

    @Test
    void invalidPhoneNumbersAndUnknownChannelsCannotSend() {
        var adapter = delivery();
        assertThatThrownBy(() -> adapter.send("SMS", "123", "123456"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.send("UNKNOWN", "0912345678", "123456"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mail);
    }

    @Test
    void smtpFailureIsNotReportedAsSuccessfulDelivery() {
        doThrow(new IllegalStateException("SMTP unavailable")).when(mail).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> delivery().send("SMS", "0912345678", "123456"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingSmtpCannotBypassDelivery() {
        var empty = new StaticListableBeanFactory();
        var adapter = new DemoSmsOtpDelivery(empty.getBeanProvider(JavaMailSender.class), new MockEnvironment(),
                "sender@example.test", "team@example.test");
        assertThatThrownBy(() -> adapter.send("SMS", "0912345678", "123456"))
                .hasMessageContaining("Configure SMTP");
    }

    @Test
    void missingInboxFailsConfiguration() {
        assertThatThrownBy(() -> new DemoSmsOtpDelivery(beans.getBeanProvider(JavaMailSender.class),
                new MockEnvironment(), "sender@example.test", ""))
                .hasMessageContaining("DEMO_SMS_INBOX");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0900000000", "0933333333", "0987654321"})
    void anyValidDemoPhoneUsesTeamInbox(String phone) {
        delivery().send("SMS", phone, "123456");
        var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(capture.capture());
        assertThat(capture.getValue().getTo()).containsExactly("team@example.test");
    }

    @Test
    void registrationForNewPhoneUsesDemoInboxAndStillRequiresCorrectOtp() {
        var users = mock(com.ddagtech.aureliabooks.repository.UserRepository.class);
        var writer = mock(com.ddagtech.aureliabooks.service.AccountSecurityService.class);
        try (var validators = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var otp = new AccountOtpServiceImpl(users, validators.getValidator(),
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4), delivery(), writer,
                    new com.ddagtech.aureliabooks.security.LoginAttemptService());
            var request = new com.ddagtech.aureliabooks.dto.request.RegisterRequest(null, "ValidPass1!", "Nguyễn An",
                    "0932222333", null, "ValidPass1!", null, true);
            String flow = otp.registration(request, "demo-session", "127.0.0.1");
            var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(mail).send(capture.capture());
            assertThat(capture.getValue().getTo()).containsExactly("team@example.test");
            verifyNoInteractions(writer);
            var matcher = java.util.regex.Pattern.compile("Mã xác thực: ([0-9]{6})").matcher(capture.getValue().getText());
            assertThat(matcher.find()).isTrue();
            assertThat(otp.verify(flow, "demo-session", matcher.group(1), "127.0.0.1").complete()).isTrue();
            verify(writer).register(argThat(draft -> "0932222333".equals(draft.phone())), eq("127.0.0.1"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ContactOtpDeliveryImpl.class, DemoSmsOtpDelivery.class})
    static class Adapters {}

    private ApplicationContextRunner context(String... profiles) {
        return new ApplicationContextRunner().withUserConfiguration(Adapters.class)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profiles))
                .withPropertyValues("demo.sms.inbox=team@example.test");
    }

    @Test
    void defaultProfileKeepsRealAdapter() {
        context().run(ctx -> {
            assertThat(ctx).hasSingleBean(ContactOtpDelivery.class);
            assertThat(ctx.getBean(ContactOtpDelivery.class)).isInstanceOf(ContactOtpDeliveryImpl.class);
        });
    }

    @Test
    void demoProfileSelectsOnlyDemoAdapter() {
        context("sms-demo").run(ctx -> {
            assertThat(ctx).hasSingleBean(ContactOtpDelivery.class);
            assertThat(ctx.getBean(ContactOtpDelivery.class)).isInstanceOf(DemoSmsOtpDelivery.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production"})
    void productionCannotStartWithDemoEnabled(String profile) {
        context("sms-demo", profile).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .hasRootCauseMessage("sms-demo must not run with prod or production");
        });
    }
}
