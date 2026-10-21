package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.entity.User;
import java.util.List;

/** Application contract for AccountOtpService; implementation lives in service.impl. */
public interface AccountOtpService {

    record View(String kind, String recipient, boolean authorized, long resendIn) {
    }

    record Result(boolean complete, String message) {
    }

    record GoogleLinkView(List<String> channels, String recipient, boolean sent, long resendIn) {
    }

    String prepareGoogleLink(Long owner, String subject, String email, String session);

    GoogleLinkView googleLinkView(String id, String session);

    void sendGoogleLink(String id, String session, String channel, String ip);

    User verifyGoogleLink(String id, String session, String code, String ip);

    String registration(RegisterRequest request, String session, String ip);

    List<String> recoveryChannels(String identifier);

    String recovery(String identifier, String channel, String session, String ip);

    String recoveryMessage();

    View view(String id, String session);

    void resend(String id, String session, String ip);

    Result verify(String id, String session, String code, String ip);

    void reset(String id, String session, String password, String confirmation, String ip);
}
