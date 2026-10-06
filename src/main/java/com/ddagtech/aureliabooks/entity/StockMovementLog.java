package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * FND-03 (physical table: stock_logs).
 * Owner: Nguyễn Trần Đức Anh.
 * Immutable stock ledger for inventory auditing and accounting (BR-01-04, BR-08-02).
 */
@Entity
@Table(name = "stock_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockMovementLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, updatable = false)
    private TransactionType transactionType;

    @Column(name = "quantity_change", nullable = false, updatable = false)
    private Integer quantityChange;

    @Column(name = "previous_stock", nullable = false, updatable = false)
    private Integer previousStock;

    @Column(name = "current_stock", nullable = false, updatable = false)
    private Integer currentStock;

    @Column(name = "reference_code", length = 50, updatable = false)
    private String referenceCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by_user_id", updatable = false)
    private User performedBy;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    public enum TransactionType {
        IMPORT,
        ORDER_DEDUCT,
        ORDER_CANCELLED_RESTOCK
    }

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PreUpdate
    public void preUpdate() {
        throw new UnsupportedOperationException("StockMovementLog is strictly immutable and cannot be updated (BR-01-04, BR-08-02).");
    }

    @PreRemove
    public void preRemove() {
        throw new UnsupportedOperationException("StockMovementLog is strictly immutable and cannot be deleted (BR-01-04, BR-08-02).");
    }
}
