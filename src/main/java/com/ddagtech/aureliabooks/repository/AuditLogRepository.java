package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data Repository for the immutable {@code audit_logs} ledger (Table 22 - UC29).
 * Extends directly from {@link Repository} rather than {@link org.springframework.data.jpa.repository.JpaRepository}
 * to prevent accidental exposure of mutation and deletion methods, enforcing invariant [BR-08-02].
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    /**
     * Appends an immutable audit log entry into the ledger.
     *
     * @param auditLog audit log entry to persist
     * @return persisted AuditLog entity
     */
    AuditLog save(AuditLog auditLog);

    /**
     * Retrieves an audit log entry by its primary key identifier.
     *
     * @param id persistent audit log ID
     * @return Optional containing the entry if found
     */
    Optional<AuditLog> findById(Long id);

    /**
     * Retrieves all audit entries paginated.
     *
     * @param pageable pagination parameters
     * @return Page of AuditLog entities
     */
    Page<AuditLog> findAll(Pageable pageable);

    /**
     * Multi-criteria paginated inquiry query utilizing LEFT JOIN FETCH to eagerly resolve operator User details
     * in a single SQL statement, eliminating the JPA N+1 query problem.
     *
     * @param action optional exact action name filter
     * @param targetTable optional exact target table name filter
     * @param startDateTime optional beginning of createdAt timestamp range
     * @param endDateTime optional end of createdAt timestamp range
     * @param keyword optional search keyword matching action, table, details, IP, or user name/email
     * @param pageable pagination and sorting parameters
     * @return Page of AuditLog entities matching criteria
     */
    @Query(
        value = "SELECT a FROM AuditLog a LEFT JOIN FETCH a.user u " +
                "WHERE (:action IS NULL OR a.action = :action) " +
                "  AND (:targetTable IS NULL OR a.targetTable = :targetTable) " +
                "  AND (:startDateTime IS NULL OR a.createdAt >= :startDateTime) " +
                "  AND (:endDateTime IS NULL OR a.createdAt <= :endDateTime) " +
                "  AND (:keyword IS NULL OR (" +
                "      LOWER(a.action) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "      OR LOWER(a.targetTable) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "      OR LOWER(a.detailsJson) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "      OR LOWER(a.ipAddress) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "      OR (u IS NOT NULL AND (" +
                "          LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "          OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "      ))" +
                "  )) " +
                "ORDER BY a.createdAt DESC",
        countQuery = "SELECT COUNT(a) FROM AuditLog a LEFT JOIN a.user u " +
                "WHERE (:action IS NULL OR a.action = :action) " +
                "  AND (:targetTable IS NULL OR a.targetTable = :targetTable) " +
                "  AND (:startDateTime IS NULL OR a.createdAt >= :startDateTime) " +
                "  AND (:endDateTime IS NULL OR a.createdAt <= :endDateTime) " +
                "  AND (:keyword IS NULL OR (" +
                "      LOWER(a.action) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "      OR LOWER(a.targetTable) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "      OR LOWER(a.detailsJson) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "      OR LOWER(a.ipAddress) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "      OR (u IS NOT NULL AND (" +
                "          LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "          OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                "      ))" +
                "  ))"
    )
    Page<AuditLog> searchLogs(
            @Param("action") String action,
            @Param("targetTable") String targetTable,
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    /**
     * Retrieves unique action descriptors from the ledger for dynamic filter rendering.
     *
     * @return sorted list of unique action strings
     */
    @Query("SELECT DISTINCT a.action FROM AuditLog a ORDER BY a.action ASC")
    List<String> findDistinctActions();

    /**
     * Retrieves unique target table names from the ledger for dynamic filter rendering.
     *
     * @return sorted list of unique target table strings
     */
    @Query("SELECT DISTINCT a.targetTable FROM AuditLog a WHERE a.targetTable IS NOT NULL ORDER BY a.targetTable ASC")
    List<String> findDistinctTargetTables();

    /**
     * Immutability guard enforcing invariant [BR-08-02]. Explicitly rejects entity deletion.
     *
     * @param entity audit log entity to delete
     * @throws UnsupportedOperationException always thrown to guard immutability
     */
    default void delete(AuditLog entity) {
        throw new UnsupportedOperationException("BẤT BIẾN NHẬT KÝ KIỂM TOÁN [BR-08-02]: Nghiêm cấm xóa bản ghi audit log.");
    }

    /**
     * Immutability guard enforcing invariant [BR-08-02]. Explicitly rejects deletion by ID.
     *
     * @param id persistent identifier
     * @throws UnsupportedOperationException always thrown to guard immutability
     */
    default void deleteById(Long id) {
        throw new UnsupportedOperationException("BẤT BIẾN NHẬT KÝ KIỂM TOÁN [BR-08-02]: Nghiêm cấm xóa bản ghi audit log theo ID.");
    }

    /**
     * Immutability guard enforcing invariant [BR-08-02]. Explicitly rejects mass table purge.
     *
     * @throws UnsupportedOperationException always thrown to guard immutability
     */
    default void deleteAll() {
        throw new UnsupportedOperationException("BẤT BIẾN NHẬT KÝ KIỂM TOÁN [BR-08-02]: Nghiêm cấm xóa trắng bảng audit log.");
    }
}
