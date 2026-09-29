package com.ddagtech.aureliabooks.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller serving the Storefront Homepage and landing experience.
 */
@Controller
public class HomeController {

    @GetMapping({"/", "/home"})
    public String index(Model model) {
        model.addAttribute("title", "Trang Chủ - Thế Giới Sách & VPP");
        return "index";
    }
}
