package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.AuditLogResponse;
import com.ddagtech.aureliabooks.entity.AuditLog;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.AuditLogRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.AuditLogServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for {@link AuditLogServiceImpl}.
 * Validates immutable audit log creation, actor relationship resolution,
 * null actor handling, credential redaction, and multi-criteria search inquiry.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private UserRepository userRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AuditLogServiceImpl auditLogService;

    @Test
    @DisplayName("record() should successfully persist audit log with actor user reference")
    void testRecord_WithActorUser_Success() {
        Long actorId = 1L;
        User actor = User.builder().id(actorId).email("admin@aureliabook.vn").build();
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

        auditLogService.record(
                actorId,
                "USER_STATUS_TOGGLE",
                "users",
                2L,
                "{\"before\":{\"active\":true},\"after\":{\"active\":false}}",
                "127.0.0.1"
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog savedLog = captor.getValue();
        assertThat(savedLog.getUser()).isEqualTo(actor);
        assertThat(savedLog.getAction()).isEqualTo("USER_STATUS_TOGGLE");
        assertThat(savedLog.getTargetTable()).isEqualTo("users");
        assertThat(savedLog.getTargetId()).isEqualTo(2L);
        assertThat(savedLog.getDetailsJson()).contains("\"active\":false");
        assertThat(savedLog.getIpAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("record() should mask sensitive credentials with [REDACTED]")
    void testRecord_WithSensitiveCredentials_MaskedSuccessfully() {
        Long actorId = 1L;
        User actor = User.builder().id(actorId).build();
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

        String rawPayload = "{\"password\":\"Secret@123\",\"token\":\"jwt-secret-xyz\",\"otp\":\"123456\",\"email\":\"test@aurelia.com\"}";

        auditLogService.record(
                actorId,
                "USER_CREATE",
                "users",
                10L,
                rawPayload,
                "127.0.0.1"
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog savedLog = captor.getValue();
        assertThat(savedLog.getDetailsJson()).contains("\"password\":\"[REDACTED]\"");
        assertThat(savedLog.getDetailsJson()).contains("\"token\":\"[REDACTED]\"");
        assertThat(savedLog.getDetailsJson()).contains("\"otp\":\"[REDACTED]\"");
        assertThat(savedLog.getDetailsJson()).contains("\"email\":\"test@aurelia.com\"");
        assertThat(savedLog.getDetailsJson()).doesNotContain("Secret@123");
    }

    @Test
    @DisplayName("record() should persist audit log when actor user ID is null (system action)")
    void testRecord_WithNullActor_Success() {
        auditLogService.record(
                null,
                "SYSTEM_PURGE",
                "sessions",
                null,
                "{\"status\":\"cleared\"}",
                "127.0.0.1"
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog savedLog = captor.getValue();
        assertThat(savedLog.getUser()).isNull();
        assertThat(savedLog.getAction()).isEqualTo("SYSTEM_PURGE");
        assertThat(savedLog.getTargetTable()).isEqualTo("sessions");
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("record() should gracefully handle repository persistence exception without propagating failure")
    void testRecord_RepositoryException_HandledGracefully() {
        Long actorId = 1L;
        User actor = User.builder().id(actorId).build();
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(auditLogRepository.save(any(AuditLog.class))).thenThrow(new RuntimeException("Database connectivity failure"));

        // Should not throw exception to caller
        auditLogService.record(
                actorId,
                "USER_STATUS_TOGGLE",
                "users",
                2L,
                "{}",
                "127.0.0.1"
        );

        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("searchLogs() should query repository with normalized parameters and map to DTO records")
    void testSearchLogs_WithFilters_Success() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        Pageable pageable = PageRequest.of(0, 10);

        User actor = User.builder().id(5L).fullName("Admin Giac").email("giac@aureliabook.vn").build();
        AuditLog log1 = new AuditLog();
        log1.setId(101L);
        log1.setUser(actor);
        log1.setAction("USER_ROLE_UPDATE");
        log1.setTargetTable("users");
        log1.setTargetId(20L);
        log1.setDetailsJson("{\"role\":\"ROLE_MANAGER\"}");
        log1.setIpAddress("192.168.1.100");
        log1.setCreatedAt(LocalDateTime.of(2026, 9, 15, 10, 0));

        Page<AuditLog> entityPage = new PageImpl<>(List.of(log1), pageable, 1);

        when(auditLogRepository.searchLogs(
                eq("USER_ROLE_UPDATE"),
                eq("users"),
                eq(start.atStartOfDay()),
                any(LocalDateTime.class),
                eq("manager"),
                eq(pageable)
        )).thenReturn(entityPage);

        Page<AuditLogResponse> resultPage = auditLogService.searchLogs(
                " USER_ROLE_UPDATE ",
                " users ",
                start,
                end,
                " manager ",
                pageable
        );

        assertThat(resultPage).isNotNull();
        assertThat(resultPage.getTotalElements()).isEqualTo(1);
        AuditLogResponse dto = resultPage.getContent().get(0);
        assertThat(dto.id()).isEqualTo(101L);
        assertThat(dto.userId()).isEqualTo(5L);
        assertThat(dto.operatorName()).isEqualTo("Admin Giac");
        assertThat(dto.operatorEmail()).isEqualTo("giac@aureliabook.vn");
        assertThat(dto.action()).isEqualTo("USER_ROLE_UPDATE");
    }

    @Test
    @DisplayName("searchLogs() with blank filter inputs should normalize to null")
    void testSearchLogs_BlankFilters_NormalizedToNull() {
        Pageable pageable = PageRequest.of(0, 20);
        when(auditLogRepository.searchLogs(null, null, null, null, null, pageable))
                .thenReturn(Page.empty());

        Page<AuditLogResponse> result = auditLogService.searchLogs(
                "  ",
                "",
                null,
                null,
                "   ",
                pageable
        );

        assertThat(result).isNotNull();
        verify(auditLogRepository).searchLogs(null, null, null, null, null, pageable);
    }

    @Test
    @DisplayName("getDistinctActions() should delegate to repository")
    void testGetDistinctActions() {
        when(auditLogRepository.findDistinctActions()).thenReturn(List.of("USER_CREATE", "LOGIN_FAILED"));
        List<String> actions = auditLogService.getDistinctActions();
        assertThat(actions).containsExactly("USER_CREATE", "LOGIN_FAILED");
    }

    @Test
    @DisplayName("getDistinctTargetTables() should delegate to repository")
    void testGetDistinctTargetTables() {
        when(auditLogRepository.findDistinctTargetTables()).thenReturn(List.of("goods_receipts", "users"));
        List<String> tables = auditLogService.getDistinctTargetTables();
        assertThat(tables).containsExactly("goods_receipts", "users");
    }
}
