package com.ddagtech.aureliabooks.service;

/** Application contract for ContactChangeService; implementation lives in service.impl. */
public interface ContactChangeService {

    record Result(boolean completed, String message, boolean loginRequired) {

        public Result(boolean completed, String message) {
            this(completed, message, completed);
        }
    }

    record View(String type, String phase, String recipient, long expiresIn, long resendIn, int attemptsLeft) {
    }

    Result startDestination(Long owner, String session, String type, String value, String ip);

    View view(Long owner, String session);

    Result resend(Long owner, String session, String ip);

    Result verify(Long owner, String session, String code, String ip);
}
