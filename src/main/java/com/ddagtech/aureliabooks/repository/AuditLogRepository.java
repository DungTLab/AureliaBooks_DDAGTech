package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.AuditLog;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.*;
import java.util.Optional;

public interface AuditLogRepository extends Repository<AuditLog, Long> {
    Optional<AuditLog> findById(Long id);
    Page<AuditLog> findAll(Pageable pageable);
    
    /**
     * Appends an immutable audit log entry into the ledger.
     *
     * @param auditLog audit log entry to persist
     * @return persisted AuditLog entity
     */
    AuditLog save(AuditLog auditLog);
}

