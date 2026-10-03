package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.entity.AuditLog;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.AuditLogRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link AuditLogService} managing immutable system audit logs (UC29).
 * Records security-sensitive administrative operations and state mutations into the audit_logs ledger.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long authenticatedUserId, String action, String targetTable,
                       Long targetId, String detailsJson, String ipAddress) {
        try {
            AuditLog auditLog = new AuditLog();
            if (authenticatedUserId != null) {
                User actor = userRepository.findById(authenticatedUserId).orElse(null);
                auditLog.setUser(actor);
            }
            auditLog.setAction(action);
            auditLog.setTargetTable(targetTable);
            auditLog.setTargetId(targetId);
            auditLog.setDetailsJson(detailsJson);
            auditLog.setIpAddress(ipAddress);

            auditLogRepository.save(auditLog);
            log.info("Audit log recorded: action={}, table={}, targetId={}, userId={}",
                    action, targetTable, targetId, authenticatedUserId);
        } catch (Exception ex) {
            log.error("Failed to persist audit log entry: action={}, table={}, targetId={}",
                    action, targetTable, targetId, ex);
        }
    }
}
