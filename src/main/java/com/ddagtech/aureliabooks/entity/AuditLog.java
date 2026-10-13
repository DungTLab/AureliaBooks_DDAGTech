package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable audit trail entity mapping table 21 ({@code audit_logs}) per [BR-08-02] and UC29.
 * Enforces append-only persistence via Hibernate {@link Immutable}, un-updatable column mappings,
 * and entity lifecycle callbacks rejecting UPDATE and DELETE operations.
 */
@Entity
@Table(name = "audit_logs")
@Immutable
@Getter
@Setter
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    @Column(name = "action", length = 50, nullable = false, updatable = false)
    private String action;

    @Column(name = "target_table", length = 50, nullable = false, updatable = false)
    private String targetTable;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json", columnDefinition = "JSON", updatable = false)
    private String detailsJson;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Prevents any update operation on an already-persisted audit log record.
     * Enforces system invariant [BR-08-02].
     */
    @PreUpdate
    public void preUpdate() {
        throw new UnsupportedOperationException("BẤT BIẾN NHẬT KÝ KIỂM TOÁN [BR-08-02]: Nghiêm cấm cập nhật bản ghi audit log.");
    }

    /**
     * Prevents any deletion operation on an already-persisted audit log record.
     * Enforces system invariant [BR-08-02].
     */
    @PreRemove
    public void preRemove() {
        throw new UnsupportedOperationException("BẤT BIẾN NHẬT KÝ KIỂM TOÁN [BR-08-02]: Nghiêm cấm xóa bản ghi audit log.");
    }
}
