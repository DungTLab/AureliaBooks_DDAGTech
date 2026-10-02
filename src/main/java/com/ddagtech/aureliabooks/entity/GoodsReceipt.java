package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** UC22/UC23. Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
@Entity
@Table(name = "goods_receipts")
@Getter
@Setter
public class GoodsReceipt extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_code", length = 32, nullable = false, unique = true)
    private String receiptCode;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.DRAFT;
    @Column(name = "total_amount", precision = 14, scale = 2, nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;
    @Column(name = "note", length = 500)
    private String note;
    @Column(name = "received_at")
    private LocalDateTime receivedAt;
    public enum Status { DRAFT, RECEIVED }
}
