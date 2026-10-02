package com.ddagtech.aureliabooks.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** UC29 (GET page shell only). Owner: Trần Huỳnh Giác. Sprint 1 scaffold; business implementation pending. */
@Controller
public class AuditLogController {
    @GetMapping("/admin/audit-logs")
    public String list() {
        // TODO UC29: populate Model through service/DTO contracts after implementation.
        return "admin/audit_logs";
    }
}
