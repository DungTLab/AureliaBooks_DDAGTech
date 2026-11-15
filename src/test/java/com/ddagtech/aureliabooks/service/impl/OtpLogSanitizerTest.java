package com.ddagtech.aureliabooks.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

class OtpLogSanitizerTest {

    @Test
    void dropsRawProviderMessagesAndCredentialsAcrossAuthorizationSchemes() {
        var ex = new MailSendException(
                "Authorization: Bearer 550abc tok123; Proxy-Authorization: Basic dXNlcjpwYXNz "
                        + "smtp://ops:secret@smtp.internal:587 port 587 <user@aureliabook.vn> otp=550123 code=654321"
        );

        String summary = OtpLogSanitizer.summarizeFailure(ex);

        assertThat(summary)
                .isEqualTo("MailSendException")
                .doesNotContain("Authorization", "Bearer", "Basic", "tok123", "550", "587",
                        "user@aureliabook.vn", "secret", "dXNlcjpwYXNz");
    }

    @Test
    void extractsOnlyAllowlistedStatusCodesAndAppErrorCodes() {
        var nested = new MailSendException(
                "Outer failure for <recipient@aureliabook.vn>",
                null,
                Map.of("msg1", new IllegalStateException("SMTP 535 5.7.8 Authentication credentials invalid"))
        );
        assertThat(OtpLogSanitizer.summarizeFailure(nested))
                .isEqualTo("MailSendException (code=535 5.7.8)")
                .doesNotContain("recipient@aureliabook.vn", "Authentication");

        var appWrapped = new IllegalStateException("wrapper", new AppException(ErrorCode.OTP_DELIVERY_FAILED));
        assertThat(OtpLogSanitizer.summarizeFailure(appWrapped))
                .isEqualTo("IllegalStateException -> AppException (errorCode=OTP_DELIVERY_FAILED)");
    }
}
