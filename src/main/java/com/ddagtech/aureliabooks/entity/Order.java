package com.ddagtech.aureliabooks.entity;

import com.ddagtech.aureliabooks.util.VNPayUtil;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Order entity mapping to 'orders' table in schema.sql.
 * Supports document-based stock posting metadata (FND-03).
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // UC11 uses the checkout token as a unique order code so repeated submissions return the same order.
    @Column(name = "order_code", length = 32, nullable = false, unique = true)
    private String orderCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Retain the source address reference for replay checks; use the snapshots below for delivery details.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name= "shipping_address_id")
    private ShippingAddress shippingAddress;

    // Capture delivery details at placement so later address edits do not change existing orders.
    @Column(name = "shipping_recipient_name", length = 100, nullable = false)
    private String shippingRecipientName;

    @Column(name = "shipping_phone", length = 15, nullable = false)
    private String shippingPhone;

    @Column(name = "shipping_full_address", length = 500, nullable = false)
    private String shippingFullAddress;

    @Column(name = "shipping_fee", precision = 10, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Column(name = "final_total_amount", precision = 12, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal finalTotalAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false)
    @Builder.Default
    private OrderStatus orderStatus = OrderStatus.PENDING_PAYMENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    // Both COD and VNPay record stock deduction at order creation; the engine checks this marker to prevent repeats (FND-03).
    @Column(name = "stock_deducted_at")
    private LocalDateTime stockDeductedAt;

    @Column(name = "stock_restored_at")
    private LocalDateTime stockRestoredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_restore_reason")
    private StockRestoreReason stockRestoreReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_restored_by_user_id")
    private User stockRestoredBy;

    // Status progression milestones
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    // VNPay reconciliation fields
    @Column(name = "vnpay_txn_ref", length = 100)
    private String vnpayTxnRef;

    @Column(name = "vnpay_pay_date", length = 20)
    private String vnpayPayDate;

    @Column(name = "vnpay_bank_code", length = 50)
    private String vnpayBankCode;

    /**
     * Captures the exact vnp_CreateDate (yyyyMMddHHmmss) sent to VNPay when initiating or retrying PAY (F10).
     * QueryDR reconciliation uses this exact timestamp as vnp_TransactionDate instead of guessing order.createdAt.
     */
    @Transient
    private String vnpayCreateDate;

    @PostLoad
    void restoreVnpayInitiationMetadata() {
        if (this.vnpayCreateDate == null && this.vnpayTxnRef != null) {
            this.vnpayCreateDate = VNPayUtil.resolvePayInitiationCreateDate(
                    this.id,
                    this.orderCode,
                    this.vnpayTxnRef
            );
        }
    }

    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    /** Synchronize both sides of the relationship; cascaded items must reference their owning order. */
    public void addItem(OrderItem item){
        this.items.add(item);
        item.setOrder(this);
    }

    public enum OrderStatus {
        PENDING_PAYMENT,
        PENDING_CONFIRMATION,
        CONFIRMED,
        SHIPPING,
        DELIVERED,
        CANCELLED
    }

    public enum PaymentMethod {
        COD,
        VNPAY
    }

    public enum PaymentStatus {
        UNPAID,
        PAID,
        REFUNDED
    }

    public enum StockRestoreReason {
        CANCELLED,
        RETURNED
    }
}
