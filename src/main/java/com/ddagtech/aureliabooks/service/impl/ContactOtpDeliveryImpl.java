package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.service.ContactOtpDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@Profile("!sms-demo")
public class ContactOtpDeliveryImpl implements ContactOtpDelivery {

    private final ObjectProvider<JavaMailSender> mail;
    private final String from;

    private final String registrationDemoInbox;

    ContactOtpDeliveryImpl(ObjectProvider<JavaMailSender> mail, String from) {
        this(mail, from, false, "");
    }

    @Autowired
    public ContactOtpDeliveryImpl(
        ObjectProvider<JavaMailSender> mail,
        @Value("${app.contact-verification.mail-from:no-reply@aureliabook.local}") String from,
        @Value("${demo.sms.registration-enabled:false}") boolean registrationDemoEnabled,
        @Value("${demo.sms.inbox:}") String inbox
    ) {
        this.mail = mail;
        this.from = from;
        this.registrationDemoInbox = registrationDemoEnabled ? DemoSmsOtpDelivery.validateInbox(inbox) : null;
    }

    @Override
    public void sendRegistrationSms(String recipient, String code) {
        if (registrationDemoInbox == null) {
            ContactOtpDelivery.super.sendRegistrationSms(recipient, code);
            return;
        }
        DemoSmsOtpDelivery.sendDemoSms(mail, from, registrationDemoInbox, recipient, code);
    }

    @Override
    public void send(String channel, String recipient, String code) {
        if (!"EMAIL".equals(channel)) throw new IllegalStateException("Unsupported delivery channel; use the configured demo adapter for phone OTP");
        var sender = mail.getIfAvailable();
        if (sender == null) throw new IllegalStateException("Email delivery unavailable");
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(OtpMailContent.SUBJECT);
        message.setText(OtpMailContent.body(code));
        sender.send(message);
    }
}
