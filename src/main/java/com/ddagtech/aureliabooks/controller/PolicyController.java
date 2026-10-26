package com.ddagtech.aureliabooks.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PolicyController {

    private static final Set<String> PAGES = Set.of("terms", "returns", "shipping", "payment", "faq");

    @GetMapping("/policies/{page}")
    public String view(@PathVariable String page, Model model, HttpServletResponse response) {
        if (!PAGES.contains(page)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        model.addAttribute("policyPage", page);
        return "policies/view";
    }
}
