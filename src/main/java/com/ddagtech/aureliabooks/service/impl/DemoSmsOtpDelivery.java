package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.service.ContactOtpDelivery;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Demo transport: all valid Vietnamese phone numbers use a private team inbox.
 */
@Service
@Profile("sms-demo")
public class DemoSmsOtpDelivery implements ContactOtpDelivery {

    private final ObjectProvider<JavaMailSender> mail;
    private final ContactOtpDelivery emailDelivery;
    private final String from;
    private final String inbox;

    public DemoSmsOtpDelivery(
        ObjectProvider<JavaMailSender> mail,
        Environment environment,
        @Value("${app.contact-verification.mail-from:no-reply@aureliabook.local}") String from,
        @Value("${demo.sms.inbox:}") String inbox
    ) {
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException("sms-demo must not run with prod or production");
        }
        try {
            InternetAddress address = new InternetAddress(inbox, true);
            address.validate();
            if (!address.getAddress().equals(inbox) || !inbox.contains("@")) {
                throw new AddressException("Invalid demo inbox");
            }
        } catch (AddressException e) {
            throw new IllegalStateException("Set DEMO_SMS_INBOX to one private team email address");
        }
        this.mail = mail;
        this.from = from;
        this.inbox = inbox;
        this.emailDelivery = new ContactOtpDeliveryImpl(mail, from, "", "", "");
    }

    @Override
    public void send(String channel, String recipient, String code) {
        if ("EMAIL".equals(channel)) {
            emailDelivery.send(channel, recipient, code);
            return;
        }
        if (!"SMS".equals(channel) || recipient == null || !recipient.matches("0[0-9]{9}")) {
            throw new IllegalStateException("SMS demo requires a valid 10-digit phone number");
        }
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) throw new IllegalStateException("Configure SMTP before using SMS demo");
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(inbox);
        message.setSubject("[SMS DEMO] " + OtpMailContent.SUBJECT);
        message.setText(
            OtpMailContent.body(code) +
                "\n[SMS GATEWAY SANDBOX - CHUYỂN TIẾP TỪ SĐT ******" +
                recipient.substring(6) +
                "]" +
                "\nThông tin bản demo: yêu cầu cho số ******" +
                recipient.substring(6) +
                ". Không xác minh quyền sở hữu SĐT thật.\n"
        );
        sender.send(message);
    }
}
