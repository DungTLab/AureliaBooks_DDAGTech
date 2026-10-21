package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "vouchers")
@Getter
@Setter
@NoArgsConstructor
public class Voucher {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 30)
    private String code;
    @Column(nullable = false, length = 255)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_scope", nullable = false)
    private Scope scope;
    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_type", nullable = false)
    private Type type;
    @Column(name = "discount_percentage", precision = 5, scale = 2)
    private BigDecimal discountPercentage;
    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount;
    @Column(name = "max_discount_amount", precision = 12, scale = 2)
    private BigDecimal maxDiscountAmount;
    @Column(name = "min_order_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal minOrderValue;
    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDateTime endDate;
    @Column(name = "usage_limit", nullable = false)
    private Integer usageLimit;
    @Column(name = "is_active", nullable = false)
    private Boolean active;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false, updatable = false)
    private User createdBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void timestamp() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }

    public enum Scope { PRODUCT, SHIPPING }
    public enum Type { PERCENTAGE, FIXED_AMOUNT }
}
