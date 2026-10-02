package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.CreationTimestamp;

/** UC29. Owner: Trần Huỳnh Giác. Sprint 1 scaffold; business implementation pending. */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;
    @Column(name = "action", length = 50, nullable = false)
    private String action;
    @Column(name = "target_table", length = 50, nullable = false)
    private String targetTable;
    @Column(name = "target_id")
    private Long targetId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json", columnDefinition = "JSON")
    private String detailsJson;
    @Column(name = "ip_address", length = 45)
    private String ipAddress;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
