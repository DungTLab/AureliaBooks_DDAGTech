package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import java.util.List;

/** Application contract for AccountOtpService; implementation lives in service.impl. */
public interface AccountOtpService {

    record View(String kind, String recipient, boolean authorized, long resendIn) {
    }

    record Result(boolean complete, String message) {
    }

    String registration(RegisterRequest request, String session, String ip);

    List<String> recoveryChannels(String identifier);

    String recovery(String identifier, String channel, String session, String ip);

    String recoveryMessage();

    View view(String id, String session);

    void resend(String id, String session, String ip);

    Result verify(String id, String session, String code, String ip);

    void reset(String id, String session, String password, String confirmation, String ip);
}
