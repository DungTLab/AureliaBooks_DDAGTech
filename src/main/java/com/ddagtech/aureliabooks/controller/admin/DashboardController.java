package com.ddagtech.aureliabooks.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Landing page for staff roles after UC06 authentication. */
@Controller
public class DashboardController {
    @GetMapping("/dashboard")
    public String dashboard() {
        return "admin/dashboard";
    }
}
