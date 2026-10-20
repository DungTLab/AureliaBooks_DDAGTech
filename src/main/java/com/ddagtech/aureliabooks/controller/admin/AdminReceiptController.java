package com.ddagtech.aureliabooks.controller.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * UC22/23/21 inbound receipt and stock alert screens.
 * Owner: Nguyễn Trần Đức Anh.
 */
@Controller
@RequiredArgsConstructor
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
