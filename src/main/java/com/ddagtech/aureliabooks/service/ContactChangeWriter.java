package com.ddagtech.aureliabooks.service;

/** Application contract for ContactChangeWriter; implementation lives in service.impl. */
public interface ContactChangeWriter {

    void apply(Long owner, String oldEmail, String oldPhone, String type, String value, String ip);

    void apply(Long owner, String oldEmail, String oldPhone, String type, String value, String ip, String currentSession);
}
