package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.response.ActorOption;
import com.ddagtech.aureliabooks.dto.response.AuditLogResponse;
import com.ddagtech.aureliabooks.entity.AuditLog;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.mapper.AuditLogMapper;
import com.ddagtech.aureliabooks.repository.AuditLogRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link AuditLogService} managing immutable system audit logs (UC29).
 * Records security-sensitive administrative operations and state mutations into the audit_logs ledger.
 */
@Slf4j
@Service
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private AuditLogMapper auditLogMapper = new AuditLogMapper();

    /**
     * Primary constructor injecting dependencies managed by Spring ApplicationContext.
     *
     * @param auditLogRepository repository managing audit logs
     * @param userRepository repository resolving actor identities
     * @param objectMapper Jackson serializer/deserializer for payload processing
     */
    @Autowired
    public AuditLogServiceImpl(AuditLogRepository auditLogRepository,
                               UserRepository userRepository,
                               ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Autowired(required = false)
    public void setAuditLogMapper(AuditLogMapper auditLogMapper) {
        if (auditLogMapper != null) {
            this.auditLogMapper = auditLogMapper;
        }
    }

    /**
     * Backward-compatible convenience constructor for unit tests and lightweight instantiation.
     *
     * @param auditLogRepository repository managing audit logs
     * @param userRepository repository resolving actor identities
     */
    public AuditLogServiceImpl(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this(auditLogRepository, userRepository, new ObjectMapper());
    }

    /**
     * Records an immutable audit log entry for an administrative state mutation.
     * Uses default transaction propagation (REQUIRED) so that the audit entry executes within the same
     * database transaction as the business operation. This ensures ACID atomicity (state change and audit log
     * commit/rollback together) and prevents lock wait timeouts (UC28-R11) caused by InnoDB foreign key shared lock
     * verification when the caller holds an exclusive pessimistic lock on the actor user.
     * Sensitive credential fields (password, token, otp) are automatically masked with "[REDACTED]".
     *
     * @param authenticatedUserId user ID of the actor performing the operation (null for system actions)
     * @param action administrative action descriptor (e.g. USER_CREATE, USER_STATUS_TOGGLE, USER_ROLE_UPDATE)
     * @param targetTable affected database table name (e.g. users)
     * @param targetId primary key identifier of the modified entity
     * @param detailsJson JSON payload diff containing state transition context
     * @param ipAddress originating client IP address
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
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
            auditLog.setDetailsJson(sanitizeDetailsJson(detailsJson));
            auditLog.setIpAddress(ipAddress);

            auditLogRepository.save(auditLog);
            log.info("Audit log recorded: action={}, table={}, targetId={}, userId={}",
                    action, targetTable, targetId, authenticatedUserId);
        } catch (Exception ex) {
            log.error("Failed to persist audit log entry: action={}, table={}, targetId={}",
                    action, targetTable, targetId, ex);
        }
    }

    /**
     * Performs multi-criteria paginated inquiry over the audit trail.
     * Normalizes date bounds to full day boundaries and eliminates N+1 queries via JOIN FETCH in the repository.
     *
     * @param action optional exact action filter
     * @param targetTable optional exact target table filter
     * @param actorId optional exact actor user ID filter (-1 for system/anonymous, positive ID for user)
     * @param startDate optional starting date of range (inclusive)
     * @param endDate optional ending date of range (inclusive)
     * @param keyword optional search term matching action, table, details, IP, or user full name/email
     * @param pageable pagination and sorting specification
     * @return Page of immutable {@link AuditLogResponse} DTO records
     */
    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> searchLogs(String action, String targetTable, Long actorId,
                                             LocalDate startDate, LocalDate endDate,
                                             String keyword, Pageable pageable) {
        String cleanAction = (action != null && !action.isBlank()) ? action.trim() : null;
        String cleanTargetTable = (targetTable != null && !targetTable.isBlank()) ? targetTable.trim() : null;
        String cleanKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;

        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDateTime = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        Page<AuditLog> entityPage = auditLogRepository.searchLogs(
                cleanAction,
                cleanTargetTable,
                actorId,
                startDateTime,
                endDateTime,
                cleanKeyword,
                pageable
        );

        return entityPage.map(auditLogMapper::toResponse);
    }

    /**
     * Retrieves unique action descriptors present in the audit ledger for dynamic filter rendering.
     *
     * @return sorted list of unique action strings
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> getDistinctActions() {
        return auditLogRepository.findDistinctActions().stream()
                .filter(action -> !"MANUAL_STOCK_ADJUST".equalsIgnoreCase(action))
                .toList();
    }

    /**
     * Retrieves unique target table names present in the audit ledger for dynamic filter rendering.
     * Excludes deprecated physical table names removed in V2.4 (such as stock_logs).
     *
     * @return sorted list of unique target table strings
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> getDistinctTargetTables() {
        return auditLogRepository.findDistinctTargetTables().stream()
                .filter(table -> !"stock_logs".equalsIgnoreCase(table))
                .toList();
    }

    /**
     * Retrieves unique actor users present in the audit ledger for dynamic dropdown rendering.
     *
     * @return sorted list of unique ActorOption records
     */
    @Override
    @Transactional(readOnly = true)
    public List<ActorOption> getDistinctActors() {
        return auditLogRepository.findDistinctActors();
    }

    /**
     * Traverses the JSON payload and masks sensitive authentication credentials and secret tokens with "[REDACTED]".
     *
     * @param detailsJson raw input JSON string
     * @return sanitized JSON string safe for audit storage
     */
    private String sanitizeDetailsJson(String detailsJson) {
        if (detailsJson == null || detailsJson.isBlank()) {
            return "{}";
        }
        try {
            JsonNode rootNode = objectMapper.readTree(detailsJson);
            redactSensitiveNodes(rootNode);
            return objectMapper.writeValueAsString(rootNode);
        } catch (Exception ex) {
            log.warn("Payload is not parseable JSON, falling back to regex masking", ex);
            return detailsJson
                    .replaceAll("(?i)(\"\\w*(?:password|token|otp|secret|cvv)\\w*\"\\s*:\\s*\")[^\"]*(\")", "$1[REDACTED]$2");
        }
    }

    /**
     * Recursively traverses JSON tree nodes and replaces sensitive credential values.
     *
     * @param node JSON node to redact
     */
    private void redactSensitiveNodes(JsonNode node) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            for (Map.Entry<String, JsonNode> field : objectNode.properties()) {
                String fieldName = field.getKey().toLowerCase();
                if (fieldName.contains("password") || fieldName.contains("token")
                        || fieldName.contains("otp") || fieldName.contains("secret")
                        || fieldName.contains("cvv")) {
                    field.setValue(new TextNode("[REDACTED]"));
                } else {
                    redactSensitiveNodes(field.getValue());
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                redactSensitiveNodes(child);
            }
        }
    }
}
