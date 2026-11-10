package com.ddagtech.aureliabooks.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactOtpDeliveryImplTest {
    @Test void emailStillGoesToTheActualRecipient() {
        var sender = mock(JavaMailSender.class);
        var beans = new StaticListableBeanFactory(); beans.addBean("mail", sender);
        var delivery = new ContactOtpDeliveryImpl(beans.getBeanProvider(JavaMailSender.class), "sender@example.test");
        delivery.send("EMAIL", "customer@example.test", "123456");
        var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(capture.capture());
        assertArrayEquals(new String[]{"customer@example.test"}, capture.getValue().getTo());
        assertTrue(capture.getValue().getText().contains("123456"));
    }
    @ParameterizedTest @ValueSource(strings = {"SMS", "UNKNOWN"})
    void unsupportedChannelsCannotSendEmailOrContactAnSmsProvider(String channel) {
        var sender = mock(JavaMailSender.class);
        var beans = new StaticListableBeanFactory(); beans.addBean("mail", sender);
        var delivery = new ContactOtpDeliveryImpl(beans.getBeanProvider(JavaMailSender.class), "sender@example.test");
        assertThrows(IllegalStateException.class, () -> delivery.send(channel, "0912345678", "123456"));
        verifyNoInteractions(sender);
    }
    @Test void explicitDemoOptInDeliversOnlyRegistrationOtpToTheTeamInbox() {
        var sender = mock(JavaMailSender.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("mail", sender);
        var mail = beans.getBeanProvider(JavaMailSender.class);
        var disabled = new ContactOtpDeliveryImpl(mail, "sender@example.test");
        assertThrows(IllegalStateException.class, () -> disabled.sendRegistrationSms("0912345678", "123456"));
        assertThrows(IllegalStateException.class, () -> new ContactOtpDeliveryImpl(mail, "sender@example.test", true, ""));
        var enabled = new ContactOtpDeliveryImpl(mail, "sender@example.test", true, "team@example.test");
        assertThrows(IllegalStateException.class, () -> enabled.send("SMS", "0912345678", "123456"));
        verifyNoInteractions(sender);

        enabled.sendRegistrationSms("0912345678", "123456");
        var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(capture.capture());
        var message = capture.getValue();
        assertArrayEquals(new String[]{"team@example.test"}, message.getTo());
        assertTrue(message.getSubject().startsWith("[SMS DEMO]"));
        assertTrue(message.getText().contains("123456"));
        assertFalse(message.getText().contains("0912345678"));
    }
    @Test void missingMailSenderFailsClosed() {
        var beans = new StaticListableBeanFactory();
        var delivery = new ContactOtpDeliveryImpl(beans.getBeanProvider(JavaMailSender.class), "sender@example.test");
        assertThrows(IllegalStateException.class, () -> delivery.send("EMAIL", "customer@example.test", "123456"));
    }
}
