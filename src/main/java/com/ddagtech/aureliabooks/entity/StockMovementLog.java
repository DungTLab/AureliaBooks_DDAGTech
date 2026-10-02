package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** FND-03 (physical table: stock_logs). Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
@Entity
@Table(name = "stock_logs")
@Getter
@Setter
public class StockMovementLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;
    @Column(name = "quantity_change", nullable = false)
    private Integer quantityChange;
    @Column(name = "previous_stock", nullable = false)
    private Integer previousStock;
    @Column(name = "current_stock", nullable = false)
    private Integer currentStock;
    @Column(name = "reference_code", length = 50)
    private String referenceCode;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by_user_id")
    private User performedBy;
    @Column(name = "note", length = 500)
    private String note;
    public enum TransactionType { IMPORT, ORDER_DEDUCT, ORDER_CANCELLED_RESTOCK, MANUAL_ADJUSTMENT }
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
