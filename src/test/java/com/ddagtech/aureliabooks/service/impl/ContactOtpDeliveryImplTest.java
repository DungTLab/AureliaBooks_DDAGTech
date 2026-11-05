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
    @Test void missingMailSenderFailsClosed() {
        var beans = new StaticListableBeanFactory();
        var delivery = new ContactOtpDeliveryImpl(beans.getBeanProvider(JavaMailSender.class), "sender@example.test");
        assertThrows(IllegalStateException.class, () -> delivery.send("EMAIL", "customer@example.test", "123456"));
    }
}
