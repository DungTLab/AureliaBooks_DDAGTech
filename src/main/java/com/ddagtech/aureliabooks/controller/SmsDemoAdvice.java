package com.ddagtech.aureliabooks.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
@Profile("sms-demo")
public class SmsDemoAdvice {

    public static final String SMS_DEMO_BANNER_MESSAGE =
            "Do không kết nối cổng viễn thông SMS thực tế, mã OTP gửi tới Số điện thoại sẽ được hệ thống chuyển tiếp giả lập về hộp thư Email demo (nhãn [SMS DEMO]).";

    @ModelAttribute("smsDemoEnabled")
    public boolean smsDemoEnabled() {
        return true;
    }

    @ModelAttribute("smsDemoBannerMessage")
    public String smsDemoBannerMessage() {
        return SMS_DEMO_BANNER_MESSAGE;
    }
}
