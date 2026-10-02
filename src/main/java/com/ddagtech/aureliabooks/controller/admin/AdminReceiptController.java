package com.ddagtech.aureliabooks.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** UC22/23/21 (GET page shell only). Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
@Controller
public class AdminReceiptController {
    @GetMapping("/staff/receipts/new")
    public String draft() {
        // TODO UC22/23/21: populate Model through service/DTO contracts after implementation.
        return "admin/receipts/form";
    }
    @GetMapping("/manager/receipts")
    public String review() {
        // TODO UC22/23/21: populate Model through service/DTO contracts after implementation.
        return "admin/receipts/list";
    }
    @GetMapping("/manager/stock/alerts")
    public String lowStock() {
        // TODO UC22/23/21: populate Model through service/DTO contracts after implementation.
        return "admin/stock/alerts";
    }
}
