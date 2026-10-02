package com.ddagtech.aureliabooks.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** UC01/02/03 (GET page shell only). Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
@Controller
public class ProductController {
    @GetMapping("/products")
    public String list() {
        // TODO UC01/02/03: populate Model through service/DTO contracts after implementation.
        return "product/list";
    }
    @GetMapping("/products/search")
    public String search() {
        // TODO UC01/02/03: populate Model through service/DTO contracts after implementation.
        return "product/search";
    }
    @GetMapping("/products/{id}")
    public String detail() {
        // TODO UC01/02/03: populate Model through service/DTO contracts after implementation.
        return "product/detail";
    }
}
