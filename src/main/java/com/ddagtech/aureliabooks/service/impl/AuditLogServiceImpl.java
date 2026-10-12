package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.entity.AuditLog;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.AuditLogRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

    /**
     * Records an immutable audit log entry for an administrative state mutation.
     * Uses default transaction propagation (REQUIRED) so that the audit entry executes within the same
     * database transaction as the business operation. This ensures ACID atomicity (state change and audit log
     * commit/rollback together) and prevents lock wait timeouts (UC28-R11) caused by InnoDB foreign key shared lock
     * verification when the caller holds an exclusive pessimistic lock on the actor user.
     *
     * @param authenticatedUserId user ID of the actor performing the operation
     * @param action administrative action descriptor (e.g. USER_CREATE, USER_STATUS_TOGGLE, USER_ROLE_UPDATE)
     * @param targetTable affected database table name (e.g. users)
     * @param targetId primary key identifier of the modified entity
     * @param detailsJson JSON payload diff containing state transition context
     * @param ipAddress originating client IP address
     */
    @Override
    @Transactional
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
