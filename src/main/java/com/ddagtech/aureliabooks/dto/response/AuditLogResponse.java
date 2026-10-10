package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.AuditLog;

import java.time.LocalDateTime;

/**
 * Immutable response DTO representing a system security audit log trail entry (UC29).
 * Used for administrative log inquiries (SCR-DB-18) and payload inspection (POP-DB-12).
 *
 * @param id persistent audit log entry identifier
 * @param userId identifier of the actor user who initiated the event (null for system/gateway)
 * @param operatorName full display name of the operator or "Hệ thống / Gateway"
 * @param operatorEmail email address of the operator or null if initiated by system
 * @param action audited action descriptor (e.g. USER_STATUS_TOGGLE, USER_ROLE_UPDATE, LOGIN_FAILED)
 * @param targetTable database table name affected by the mutation
 * @param targetId primary key identifier of the affected database record
 * @param detailsJson structured JSON payload containing mutation context or before/after diff
 * @param ipAddress client IP address originating the request (CWE-290 sanitized)
 * @param createdAt timestamp when the audit event was committed
 */
public record AuditLogResponse(
        Long id,
        Long userId,
        String operatorName,
        String operatorEmail,
        String action,
        String targetTable,
        Long targetId,
        String detailsJson,
        String ipAddress,
        LocalDateTime createdAt
) {
    /**
     * Factory mapping method converting a managed {@link AuditLog} entity into an immutable record.
     * Gracefully handles null operator references by defaulting operator name to "Hệ thống / Gateway".
     *
     * @param entity source AuditLog entity
     * @return populated AuditLogResponse record
     */
    public static AuditLogResponse fromEntity(AuditLog entity) {
        if (entity == null) {
            return null;
        }
        String name = entity.getUser() != null ? entity.getUser().getFullName() : "Hệ thống / Gateway";
        String email = entity.getUser() != null ? entity.getUser().getEmail() : null;
        Long uid = entity.getUser() != null ? entity.getUser().getId() : null;

        return new AuditLogResponse(
                entity.getId(),
                uid,
                name,
                email,
                entity.getAction(),
                entity.getTargetTable(),
                entity.getTargetId(),
                entity.getDetailsJson(),
                entity.getIpAddress(),
                entity.getCreatedAt()
        );
    }
}
