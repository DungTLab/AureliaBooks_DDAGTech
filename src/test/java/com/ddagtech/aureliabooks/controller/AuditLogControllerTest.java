package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.admin.AuditLogController;
import com.ddagtech.aureliabooks.dto.response.ActorOption;
import com.ddagtech.aureliabooks.dto.response.AuditLogResponse;
import com.ddagtech.aureliabooks.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller and Web Security test suite for {@link AuditLogController} (UC29).
 * Validates ROLE_ADMIN access, non-admin 403 authorization rejections,
 * unauthenticated redirects, and model attribute binding.
 */
@WebMvcTest(controllers = AuditLogController.class)
@Import(SecurityConfig.class)
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    @Test
    @DisplayName("GET /admin/audit-logs as ADMIN returns 200 OK and binds model attributes")
    void testListAuditLogs_Admin_Success() throws Exception {
        AuditLogResponse mockLog = new AuditLogResponse(
                1L, 2L, "Admin User", "admin@aureliabook.vn",
                "USER_STATUS_TOGGLE", "users", 5L,
                "{\"active\":false}", "127.0.0.1", LocalDateTime.now()
        );
        Page<AuditLogResponse> mockPage = new PageImpl<>(List.of(mockLog), PageRequest.of(0, 20), 1);

        when(auditLogService.searchLogs(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockPage);
        when(auditLogService.getDistinctActions())
                .thenReturn(List.of("USER_STATUS_TOGGLE", "USER_ROLE_UPDATE"));
        when(auditLogService.getDistinctTargetTables())
                .thenReturn(List.of("users", "orders"));
        when(auditLogService.getDistinctActors())
                .thenReturn(List.of(new ActorOption(2L, "Admin User", "admin@aureliabook.vn")));

        mockMvc.perform(get("/admin/audit-logs")
                        .with(user("admin").roles("ADMIN"))
                        .param("action", "USER_STATUS_TOGGLE")
                        .param("actorId", "2")
                        .param("keyword", "admin")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-30")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/audit_logs"))
                .andExpect(model().attributeExists("logsPage"))
                .andExpect(model().attributeExists("actions"))
                .andExpect(model().attributeExists("targetTables"))
                .andExpect(model().attributeExists("actors"))
                .andExpect(model().attribute("selectedAction", "USER_STATUS_TOGGLE"))
                .andExpect(model().attribute("selectedActorId", 2L))
                .andExpect(model().attribute("keyword", "admin"))
                .andExpect(model().attribute("startDate", LocalDate.of(2026, 9, 1)))
                .andExpect(model().attribute("endDate", LocalDate.of(2026, 9, 30)));

        verify(auditLogService).searchLogs(
                eq("USER_STATUS_TOGGLE"),
                isNull(),
                eq(2L),
                eq(LocalDate.of(2026, 9, 1)),
                eq(LocalDate.of(2026, 9, 30)),
                eq("admin"),
                any()
        );
    }

    @Test
    @DisplayName("GET /admin/audit-logs as CUSTOMER returns 403 Forbidden")
    void testListAuditLogs_Customer_Forbidden() throws Exception {
        mockMvc.perform(get("/admin/audit-logs").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/audit-logs as SALE_STAFF returns 403 Forbidden")
    void testListAuditLogs_SaleStaff_Forbidden() throws Exception {
        mockMvc.perform(get("/admin/audit-logs").with(user("staff").roles("SALE_STAFF")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/audit-logs as MANAGER returns 403 Forbidden")
    void testListAuditLogs_Manager_Forbidden() throws Exception {
        mockMvc.perform(get("/admin/audit-logs").with(user("manager").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/audit-logs without authentication redirects to login")
    void testListAuditLogs_Unauthenticated_Redirects() throws Exception {
        mockMvc.perform(get("/admin/audit-logs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/internal/login"));
    }

    @Test
    @DisplayName("GET /admin/audit-logs with custom page size passes valid size to service")
    void testListAuditLogs_WithCustomPageSize_PassedToService() throws Exception {
        when(auditLogService.searchLogs(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty());
        when(auditLogService.getDistinctActions()).thenReturn(List.of());
        when(auditLogService.getDistinctTargetTables()).thenReturn(List.of());
        when(auditLogService.getDistinctActors()).thenReturn(List.of());

        mockMvc.perform(get("/admin/audit-logs")
                        .with(user("admin").roles("ADMIN"))
                        .param("size", "10")
                        .param("page", "2"))
                .andExpect(status().isOk());

        verify(auditLogService).searchLogs(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(PageRequest.of(2, 10, org.springframework.data.domain.Sort.by("createdAt").descending()))
        );
    }
}
