package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** UC22/UC23. Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
@Entity
@Table(name = "goods_receipt_items")
@Getter
@Setter
public class GoodsReceiptItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private GoodsReceipt receipt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
    @Column(name = "received_quantity", nullable = false)
    private Integer receivedQuantity;
    @Column(name = "unit_cost", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitCost;
}
