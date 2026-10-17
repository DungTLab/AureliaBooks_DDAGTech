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
                "sender@example.test", "team@example.test", "0912345678,0987654321");
    }

    @Test
    void approvedSmsOnlyGoesToPrivateTeamInboxAndIsClearlyLabelled() {
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
    void numbersOutsideAllowlistAndUnknownChannelsCannotSend() {
        var adapter = delivery();
        assertThatThrownBy(() -> adapter.send("SMS", "0900000000", "123456"))
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
                "sender@example.test", "team@example.test", "0912345678");
        assertThatThrownBy(() -> adapter.send("SMS", "0912345678", "123456"))
                .hasMessageContaining("Configure SMTP");
    }

    @Test
    void missingInboxOrInvalidAllowlistFailsConfiguration() {
        assertThatThrownBy(() -> new DemoSmsOtpDelivery(beans.getBeanProvider(JavaMailSender.class),
                new MockEnvironment(), "sender@example.test", "", "0912345678"))
                .hasMessageContaining("DEMO_SMS_INBOX");
        assertThatThrownBy(() -> new DemoSmsOtpDelivery(beans.getBeanProvider(JavaMailSender.class),
                new MockEnvironment(), "sender@example.test", "team@example.test", ""))
                .hasMessageContaining("DEMO_SMS_ALLOWED_PHONES");
        assertThatThrownBy(() -> new DemoSmsOtpDelivery(beans.getBeanProvider(JavaMailSender.class),
                new MockEnvironment(), "sender@example.test", "team@example.test", "123"))
                .hasMessageContaining("DEMO_SMS_ALLOWED_PHONES");
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ContactOtpDeliveryImpl.class, DemoSmsOtpDelivery.class})
    static class Adapters {}

    private ApplicationContextRunner context(String... profiles) {
        return new ApplicationContextRunner().withUserConfiguration(Adapters.class)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profiles))
                .withPropertyValues("demo.sms.inbox=team@example.test",
                        "demo.sms.allowed-phones=0912345678");
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
