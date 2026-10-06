package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.AuditLog;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.AuditLogRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.AuditLogServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for {@link AuditLogServiceImpl}.
 * Validates immutable audit log creation, actor relationship resolution,
 * null actor handling, and error resilience.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private UserRepository userRepository;

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
}
