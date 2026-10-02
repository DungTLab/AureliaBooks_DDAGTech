package com.ddagtech.aureliabooks.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** UC28 (GET page shell only). Owner: Trần Huỳnh Giác. Sprint 1 scaffold; business implementation pending. */
@Controller
public class AdminUserController {
    @GetMapping("/admin/users")
    public String list() {
        // TODO UC28: populate Model through service/DTO contracts after implementation.
        return "admin/users/list";
    }
}
