package com.ddagtech.aureliabooks.service;

/** Delivery adapter; successful return means the provider accepted the message. */
public interface ContactOtpDelivery {
    void send(String channel, String recipient, String code);

    default void sendRegistrationSms(String recipient, String code) {
        send("SMS", recipient, code);
    }
}
