package com.ddagtech.aureliabooks.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** UC15/16 (GET page shell only). Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
@Controller
public class AdminProductController {
    @GetMapping("/manager/products")
    public String list() {
        // TODO UC15/16: populate Model through service/DTO contracts after implementation.
        return "admin/products/list";
    }
    @GetMapping("/manager/products/new")
    public String form() {
        // TODO UC15/16: populate Model through service/DTO contracts after implementation.
        return "admin/products/form";
    }
}
