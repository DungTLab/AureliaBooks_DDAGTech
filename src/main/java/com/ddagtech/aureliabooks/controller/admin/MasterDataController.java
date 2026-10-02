package com.ddagtech.aureliabooks.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** UC17/18/19/20 (GET page shell only). Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
@Controller
public class MasterDataController {
    @GetMapping("/manager/authors")
    public String authors() {
        // TODO UC17/18/19/20: populate Model through service/DTO contracts after implementation.
        return "admin/master_data/list";
    }
    @GetMapping("/manager/publishers")
    public String publishers() {
        // TODO UC17/18/19/20: populate Model through service/DTO contracts after implementation.
        return "admin/master_data/list";
    }
    @GetMapping("/manager/brands")
    public String brands() {
        // TODO UC17/18/19/20: populate Model through service/DTO contracts after implementation.
        return "admin/master_data/list";
    }
    @GetMapping("/manager/categories")
    public String categories() {
        // TODO UC17/18/19/20: populate Model through service/DTO contracts after implementation.
        return "admin/categories/tree";
    }
}
