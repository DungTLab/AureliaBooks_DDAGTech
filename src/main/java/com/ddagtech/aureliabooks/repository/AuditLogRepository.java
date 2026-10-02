package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.AuditLog;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.*;
import java.util.Optional;

public interface AuditLogRepository extends Repository<AuditLog, Long> {
    Optional<AuditLog> findById(Long id);
    Page<AuditLog> findAll(Pageable pageable);
    // TODO: controlled append belongs to the transactional service; no generic save/delete API.
}
