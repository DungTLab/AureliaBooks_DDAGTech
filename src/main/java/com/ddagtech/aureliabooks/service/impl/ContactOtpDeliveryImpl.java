package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.service.ContactOtpDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Service
@Profile("!sms-demo")
public class ContactOtpDeliveryImpl implements ContactOtpDelivery {
    private final ObjectProvider<JavaMailSender> mail;
    private final String from, sid, token, smsFrom;
    private final RestClient http;

    public ContactOtpDeliveryImpl(ObjectProvider<JavaMailSender> mail,
                                  @Value("${app.contact-verification.mail-from:no-reply@aureliabook.local}") String from,
                                  @Value("${app.contact-verification.twilio-account-sid:}") String sid,
                                  @Value("${app.contact-verification.twilio-auth-token:}") String token,
                                  @Value("${app.contact-verification.twilio-from:}") String smsFrom) {
        this.mail = mail;
        this.from = from;
        this.sid = sid;
        this.token = token;
        this.smsFrom = smsFrom;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void send(String channel, String recipient, String code) {
        String body = "Mã xác thực tài khoản AureliaBook: " + code + ". Mã có hiệu lực 5 phút. Không chia sẻ mã với bất kỳ ai.";
        if ("EMAIL".equals(channel)) {
            var sender = mail.getIfAvailable();
            if (sender == null) throw new IllegalStateException("Email delivery unavailable");
            var message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(recipient);
            message.setSubject(OtpMailContent.SUBJECT);
            message.setText(OtpMailContent.body(code));
            sender.send(message);
        } else {
            if (sid.isBlank() || token.isBlank() || smsFrom.isBlank())
                throw new IllegalStateException("SMS delivery unavailable");
            var form = new LinkedMultiValueMap<String, String>();
            form.add("To", "+84" + recipient.substring(1));
            form.add("From", smsFrom);
            form.add("Body", body);
            http.post().uri("https://api.twilio.com/2010-04-01/Accounts/{sid}/Messages.json", sid)
                    .headers(h -> h.setBasicAuth(sid, token)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().toBodilessEntity();
        }
    }
}
