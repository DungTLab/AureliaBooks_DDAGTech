package com.ddagtech.aureliabooks.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** UC05/06/07 (GET page shell only). Owner: Lê Tiến Dũng. Sprint 1 scaffold; business implementation pending. */
@Controller
public class AuthController {
    @GetMapping("/auth/login")
    public String login() {
        // TODO UC05/06/07: populate Model through service/DTO contracts after implementation.
        return "auth/login";
    }
    @GetMapping("/auth/register")
    public String register() {
        // TODO UC05/06/07: populate Model through service/DTO contracts after implementation.
        return "auth/register";
    }
}
