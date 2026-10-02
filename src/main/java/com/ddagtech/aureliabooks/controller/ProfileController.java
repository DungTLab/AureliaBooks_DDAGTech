package com.ddagtech.aureliabooks.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** UC08/09 (GET page shell only). Owner: Lê Tiến Dũng. Sprint 1 scaffold; business implementation pending. */
@Controller
public class ProfileController {
    @GetMapping("/profile")
    public String profile() {
        // TODO UC08/09: populate Model through service/DTO contracts after implementation.
        return "profile/view";
    }
    @GetMapping("/profile/addresses")
    public String addresses() {
        // TODO UC08/09: populate Model through service/DTO contracts after implementation.
        return "profile/addresses";
    }
}
