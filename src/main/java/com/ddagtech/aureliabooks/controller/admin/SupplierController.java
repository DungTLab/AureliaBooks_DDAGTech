package com.ddagtech.aureliabooks.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** UC30 (GET page shell only). Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
@Controller
public class SupplierController {
    @GetMapping("/manager/suppliers")
    public String list() {
        // TODO UC30: populate Model through service/DTO contracts after implementation.
        return "admin/suppliers/list";
    }
}
