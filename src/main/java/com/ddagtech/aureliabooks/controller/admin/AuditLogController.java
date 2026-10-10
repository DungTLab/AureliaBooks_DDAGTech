package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.response.AuditLogResponse;
import com.ddagtech.aureliabooks.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

/**
 * Controller managing administrative inspection of the system security audit trail (UC29).
 * Realizes Wireframe SCR-DB-18 (Audit Trail Console) and POP-DB-12 (Payload Inspection Modal).
 * Access is protected by the Spring Security filter chain and strictly restricted to ROLE_ADMIN.
 */
@Slf4j
@Controller
@RequestMapping("/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * Renders the paginated audit logs inquiry console with multi-criteria filtering.
     *
     * @param action optional exact action name filter
     * @param targetTable optional exact target table name filter
     * @param startDate optional starting date of range (inclusive)
     * @param endDate optional ending date of range (inclusive)
     * @param keyword optional search term matching action, table, details, IP, or user name/email
     * @param page zero-based page index (defaults to 0)
     * @param size page size (defaults to 20 per Wireframe SCR-DB-18)
     * @param model MVC model
     * @return view name "admin/audit_logs"
     */
    @GetMapping
    public String list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetTable,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model
    ) {
        int validPage = Math.max(0, page);
        int validSize = (size > 0 && size <= 100) ? size : 20;

        Page<AuditLogResponse> logsPage = auditLogService.searchLogs(
                action,
                targetTable,
                startDate,
                endDate,
                keyword,
                PageRequest.of(validPage, validSize, Sort.by("createdAt").descending())
        );

        List<String> actions = auditLogService.getDistinctActions();
        List<String> targetTables = auditLogService.getDistinctTargetTables();

        model.addAttribute("logsPage", logsPage);
        model.addAttribute("actions", actions);
        model.addAttribute("targetTables", targetTables);
        model.addAttribute("selectedAction", action);
        model.addAttribute("selectedTargetTable", targetTable);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("keyword", keyword);

        log.debug("Rendered audit logs console: totalElements={}, page={}/{}",
                logsPage.getTotalElements(), validPage, logsPage.getTotalPages());

        return "admin/audit_logs";
    }
}
