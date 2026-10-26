package com.ddagtech.aureliabooks.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Dedicated password-only entrance for business staff. */
@Controller
public class InternalPortalController {
    @GetMapping("/internal/login")
    public String login() {
        return "internal/login";
    }

    @GetMapping({"/internal", "/internal/"})
    public String workspace() {
        return "redirect:/dashboard";
    }
}
