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

    @Test
    void preservesContextualStatusCodesWithOrWithoutSpacingWhileDroppingHostPortsAndSecrets() {
        var compactStatus = new IllegalStateException(
                "Authorization: Bearer tok999; relay.invalid:587 failed for <user@aureliabook.vn> "
                        + "with status:429 Too Many Requests otp=654321"
        );
        var spacedStatus = new IllegalStateException(
                "Authorization: Bearer tok999; relay.invalid:587 failed for <user@aureliabook.vn> "
                        + "with status: 429 Too Many Requests otp=654321"
        );
        assertThat(OtpLogSanitizer.summarizeFailure(compactStatus))
                .isEqualTo("IllegalStateException (code=429)")
                .isEqualTo(OtpLogSanitizer.summarizeFailure(spacedStatus))
                .doesNotContain("tok999", "587", "relay.invalid", "user@aureliabook.vn", "Too Many Requests", "654321");

        var jsonStatus = new IllegalStateException("{\"httpStatus\":503,\"detail\":\"secret=abc\"}");
        assertThat(OtpLogSanitizer.summarizeFailure(jsonStatus))
                .isEqualTo("IllegalStateException (code=503)")
                .doesNotContain("secret", "abc");

        var hostPortOnly = new IllegalStateException(
                "Connection refused to relay.invalid:587 api.status:500 smtp:587 Authorization: Basic dXNlcjpwYXNz"
        );
        assertThat(OtpLogSanitizer.summarizeFailure(hostPortOnly))
                .isEqualTo("IllegalStateException")
                .doesNotContain("587", "500", "relay.invalid", "dXNlcjpwYXNz");
    }
}
