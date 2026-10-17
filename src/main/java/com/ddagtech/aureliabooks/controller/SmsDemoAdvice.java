package com.ddagtech.aureliabooks.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
@Profile("sms-demo")
public class SmsDemoAdvice {
    @ModelAttribute("smsDemoEnabled")
    public boolean smsDemoEnabled() {
        return true;
    }
}
