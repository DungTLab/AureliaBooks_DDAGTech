package com.ddagtech.aureliabooks.service;

/** Delivery adapter; successful return means the provider accepted the message. */
public interface ContactOtpDelivery {
    void send(String channel, String recipient, String code);
}
