package com.ddagtech.aureliabooks.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class SmsDemoAdvice {

    private final boolean demoProfile;
    private final boolean registrationDemo;

    public SmsDemoAdvice(Environment environment,
            @Value("${demo.sms.registration-enabled:false}") boolean registrationDemo) {
        this.demoProfile = environment.acceptsProfiles(Profiles.of("sms-demo"));
        this.registrationDemo = registrationDemo;
    }

    @ModelAttribute("smsDemoRegistrationEnabled")
    public boolean smsDemoRegistrationEnabled() {
        return demoProfile || registrationDemo;
    }

    public static final String SMS_DEMO_BANNER_MESSAGE =
            "Do không kết nối cổng viễn thông SMS thực tế, mã OTP gửi tới Số điện thoại sẽ được hệ thống chuyển tiếp giả lập về hộp thư Email demo (nhãn [SMS DEMO]).";

    @ModelAttribute("smsDemoEnabled")
    public boolean smsDemoEnabled() {
        return demoProfile;
    }

    @ModelAttribute("smsDemoBannerMessage")
    public String smsDemoBannerMessage() {
        return SMS_DEMO_BANNER_MESSAGE;
    }
}
