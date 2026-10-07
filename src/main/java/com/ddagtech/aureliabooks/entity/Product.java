package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Formula;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "barcode", length = 50, nullable = false, unique = true)
    private String barcode;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false)
    private ProductType productType;

    @Column(name = "price", precision = 12, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(name = "original_cost", precision = 12, scale = 2, nullable = false)
    private BigDecimal originalCost;

    @Column(name = "weight_grams", nullable = false)
    @Builder.Default
    private Integer weightGrams = 200;

    @Column(name = "stock_quantity", nullable = false)
    @Builder.Default
    private Integer stockQuantity = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "main_image_url", length = 500)
    private String mainImageUrl;

    @Column(name = "tags", length = 255)
    private String tags;

    @Column(name = "description", columnDefinition = "LONGTEXT")
    private String description;

    // Schema field only. Semantic recommendation belongs to a later sprint.
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "embedding", columnDefinition = "JSON")
    private String embedding;

    /**
     * Total quantity of items sold in confirmed, shipping, or delivered orders.
     * Evaluated dynamically via Hibernate formula subquery from order_items and orders tables.
     */
    @Formula("(SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi JOIN orders o ON oi.order_id = o.id WHERE oi.product_id = id AND o.order_status IN ('CONFIRMED', 'SHIPPING', 'DELIVERED'))")
    @Builder.Default
    private Long totalSold = 0L;

    public enum ProductType {
        BOOK, STATIONERY
    }
}
