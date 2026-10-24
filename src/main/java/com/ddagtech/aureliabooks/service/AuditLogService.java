package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.ActorOption;
import com.ddagtech.aureliabooks.dto.response.AuditLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

/**
 * Service managing the immutable system security audit log trail (UC29).
 * Records security-critical mutations and provides filtered multi-criteria audit inquiries.
 */
public interface AuditLogService {

    /**
     * Records an immutable audit log entry into the ledger within the caller's active database transaction.
     * Automatically redacts sensitive fields (passwords, tokens, OTPs) from the details payload.
     *
     * @param authenticatedUserId operator user ID or null for system/anonymous actions
     * @param action audited action descriptor (e.g. USER_STATUS_TOGGLE, LOGIN_FAILED, VNPAY_IPN_CALLBACK)
     * @param targetTable database table affected
     * @param targetId primary key identifier of the modified record
     * @param detailsJson raw or structured JSON details
     * @param ipAddress originating client IP address
     */
    void record(Long authenticatedUserId, String action, String targetTable,
                Long targetId, String detailsJson, String ipAddress);

    /** Required business ledger entry: persistence failure must roll back the caller's transaction. */
    void recordRequired(Long authenticatedUserId, String action, String targetTable,
                        Long targetId, String detailsJson, String ipAddress);

    /**
     * Performs multi-criteria paginated search over audit entries.
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
    Page<AuditLogResponse> searchLogs(String action, String targetTable, Long actorId,
                                      LocalDate startDate, LocalDate endDate,
                                      String keyword, Pageable pageable);

    /**
     * Retrieves unique action descriptors present in the audit ledger for dynamic dropdown rendering.
     *
     * @return sorted list of unique action strings
     */
    List<String> getDistinctActions();

    /**
     * Retrieves unique target table names present in the audit ledger for dynamic dropdown rendering.
     *
     * @return sorted list of unique target table strings
     */
    List<String> getDistinctTargetTables();

    /**
     * Retrieves unique actor users present in the audit ledger for dynamic dropdown rendering.
     *
     * @return sorted list of unique ActorOption records
     */
    List<ActorOption> getDistinctActors();
}
