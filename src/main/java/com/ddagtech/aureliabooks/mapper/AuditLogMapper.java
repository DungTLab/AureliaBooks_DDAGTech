package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.response.AuditLogResponse;
import com.ddagtech.aureliabooks.entity.AuditLog;
import org.springframework.stereotype.Component;

/**
 * Stateless mapper for AuditLog entity to AuditLogResponse DTO conversions (UC29).
 */
@Component
public class AuditLogMapper {

    public AuditLogResponse toResponse(AuditLog entity) {
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
