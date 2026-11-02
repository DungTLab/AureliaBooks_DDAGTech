package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Prefix used to persist the 14-digit PAY initiation timestamp (vnp_CreateDate) durably inside
     * the existing orders.vnpay_pay_date VARCHAR(20) column while the order is UNPAID ("INIT:" + 14 digits = 19 chars).
     * Once payment execution is confirmed (PAID or late-success reconciliation), vnpay_pay_date is overwritten
     * with the actual gateway vnp_PayDate.
     */
    public static final String PAY_INIT_DATE_PREFIX = "INIT:";

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
     * Exact vnp_CreateDate (yyyyMMddHHmmss) sent to VNPay when initiating or retrying PAY (F10).
     * Synchronized with the database column vnpay_pay_date using {@link #PAY_INIT_DATE_PREFIX} while UNPAID
     * so that QueryDR reconciliation across JVM restarts reads the exact timestamp from MySQL.
     */
    @Transient
    private String vnpayCreateDate;

    /**
     * Chronological ledger of all initiated VNPay payment attempts (vnp_TxnRef -> vnp_CreateDate) for this order.
     * Synchronized with vnpay_bank_code using {@link #PAY_INIT_DATE_PREFIX} while the order is UNPAID so that
     * CRON-01 QueryDR reconciles every prior attempt before cancelling or restoring inventory.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<String, String> vnpayAttemptHistory = new LinkedHashMap<>();

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Set<String> settledVnpayTxnRefs = new LinkedHashSet<>();

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Set<String> settledVnpayTransactionNos = new LinkedHashSet<>();

    public record VnpayPaymentAttempt(String txnRef, String createDate) {}

    public void setVnpayTxnRef(String vnpayTxnRef) {
        if (this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()) {
            String existingCreate = getVnpayCreateDate();
            if (existingCreate != null && !existingCreate.isBlank()) {
                this.vnpayAttemptHistory.putIfAbsent(this.vnpayTxnRef.trim(), existingCreate);
            }
        }
        this.vnpayTxnRef = vnpayTxnRef;
    }

    public String getVnpayBankCode() {
        if (this.vnpayBankCode != null && this.vnpayBankCode.startsWith(PAY_INIT_DATE_PREFIX)) {
            return null;
        }
        return this.vnpayBankCode;
    }

    public void setVnpayBankCode(String vnpayBankCode) {
        this.vnpayBankCode = vnpayBankCode;
        if (vnpayBankCode != null && vnpayBankCode.startsWith(PAY_INIT_DATE_PREFIX)) {
            decodeAttemptsFromBankCodeInto(this.vnpayAttemptHistory);
        }
    }

    public String getVnpayPayDate() {
        if (this.vnpayPayDate != null && this.vnpayPayDate.startsWith(PAY_INIT_DATE_PREFIX)) {
            return null;
        }
        return this.vnpayPayDate;
    }

    public void setVnpayPayDate(String vnpayPayDate) {
        this.vnpayPayDate = vnpayPayDate;
        if (vnpayPayDate != null && vnpayPayDate.startsWith(PAY_INIT_DATE_PREFIX)) {
            this.vnpayCreateDate = vnpayPayDate.substring(PAY_INIT_DATE_PREFIX.length()).trim();
        }
    }

    public String getVnpayCreateDate() {
        if (this.vnpayCreateDate != null && !this.vnpayCreateDate.isBlank()) {
            return this.vnpayCreateDate.trim();
        }
        if (this.vnpayPayDate != null && this.vnpayPayDate.startsWith(PAY_INIT_DATE_PREFIX)) {
            String extracted = this.vnpayPayDate.substring(PAY_INIT_DATE_PREFIX.length()).trim();
            return extracted.isEmpty() ? null : extracted;
        }
        return null;
    }

    public void setVnpayCreateDate(String vnpayCreateDate) {
        if (vnpayCreateDate == null || vnpayCreateDate.isBlank()) {
            this.vnpayCreateDate = null;
            if (this.vnpayPayDate != null && this.vnpayPayDate.startsWith(PAY_INIT_DATE_PREFIX)) {
                this.vnpayPayDate = null;
            }
            return;
        }
        String clean = vnpayCreateDate.trim();
        this.vnpayCreateDate = clean;
        if (this.vnpayPayDate == null || this.vnpayPayDate.isBlank() || this.vnpayPayDate.startsWith(PAY_INIT_DATE_PREFIX)) {
            this.vnpayPayDate = PAY_INIT_DATE_PREFIX + clean;
        }
        if (this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()) {
            this.vnpayAttemptHistory.put(this.vnpayTxnRef.trim(), clean);
            syncEncodedAttemptsToBankCodeIfUnpaid();
        }
    }

    /**
     * Atomically records a newly initiated VNPay payment attempt (vnp_TxnRef + vnp_CreateDate)
     * while preserving all prior attempts for multi-attempt QueryDR reconciliation and retry uniqueness.
     */
    public void recordVnpayPaymentAttempt(String txnRef, String createDate) {
        setVnpayTxnRef(txnRef);
        setVnpayCreateDate(createDate);
    }

    /**
     * Returns an immutable chronological list of all initiated VNPay payment attempts for this order.
     */
    public List<VnpayPaymentAttempt> getVnpayPaymentAttempts() {
        Map<String, String> merged = new LinkedHashMap<>();
        decodeAttemptsFromBankCodeInto(merged);
        for (Map.Entry<String, String> entry : this.vnpayAttemptHistory.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()
                    && entry.getValue() != null && !entry.getValue().isBlank()) {
                merged.put(entry.getKey().trim(), entry.getValue().trim());
            }
        }
        String currentRef = this.vnpayTxnRef != null ? this.vnpayTxnRef.trim() : null;
        String currentCreateDate = getVnpayCreateDate();
        if (currentRef != null && !currentRef.isEmpty()
                && currentCreateDate != null && !currentCreateDate.isEmpty()) {
            merged.putIfAbsent(currentRef, currentCreateDate);
        }
        List<VnpayPaymentAttempt> result = new ArrayList<>(merged.size());
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            result.add(new VnpayPaymentAttempt(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    /**
     * Records a settled/confirmed VNPay transaction reference and gateway transaction number
     * to distinguish true duplicate IPN callbacks from a second distinct successful payment attempt.
     */
    public void recordSettledVnpayAttempt(String txnRef, String transactionNo) {
        if (txnRef != null && !txnRef.isBlank()) {
            this.settledVnpayTxnRefs.add(txnRef.trim());
        }
        if (transactionNo != null && !transactionNo.isBlank()) {
            this.settledVnpayTransactionNos.add(transactionNo.trim());
        }
    }

    /**
     * Determines whether an incoming verified-success IPN represents a distinct successful payment
     * (surplus payment on another retry attempt or gateway transaction) rather than a duplicate callback
     * for an already confirmed transaction.
     */
    public boolean isDistinctSuccessfulVnpayAttempt(String txnRef, String transactionNo) {
        String cleanRef = txnRef != null ? txnRef.trim() : "";
        String cleanTxNo = transactionNo != null ? transactionNo.trim() : "";

        Set<String> knownSettledRefs = new LinkedHashSet<>(this.settledVnpayTxnRefs);
        if (this.paymentStatus != PaymentStatus.UNPAID
                && this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()) {
            knownSettledRefs.add(this.vnpayTxnRef.trim());
        }

        if (!cleanRef.isEmpty() && !knownSettledRefs.isEmpty() && !knownSettledRefs.contains(cleanRef)) {
            return true;
        }
        if (!cleanTxNo.isEmpty() && !this.settledVnpayTransactionNos.isEmpty()
                && !this.settledVnpayTransactionNos.contains(cleanTxNo)) {
            return true;
        }
        return false;
    }

    @PrePersist
    @PreUpdate
    void syncVnpayInitiationMetadataBeforeWrite() {
        if (this.vnpayCreateDate != null && !this.vnpayCreateDate.isBlank()) {
            if (this.vnpayPayDate == null || this.vnpayPayDate.isBlank() || this.vnpayPayDate.startsWith(PAY_INIT_DATE_PREFIX)) {
                this.vnpayPayDate = PAY_INIT_DATE_PREFIX + this.vnpayCreateDate.trim();
            }
        }
        syncEncodedAttemptsToBankCodeIfUnpaid();
    }

    @PostLoad
    void restoreVnpayInitiationMetadata() {
        if (this.vnpayPayDate != null && this.vnpayPayDate.startsWith(PAY_INIT_DATE_PREFIX)) {
            this.vnpayCreateDate = this.vnpayPayDate.substring(PAY_INIT_DATE_PREFIX.length()).trim();
        }
        decodeAttemptsFromBankCodeInto(this.vnpayAttemptHistory);
        if (this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()
                && this.vnpayCreateDate != null && !this.vnpayCreateDate.isBlank()) {
            this.vnpayAttemptHistory.putIfAbsent(this.vnpayTxnRef.trim(), this.vnpayCreateDate);
        }
    }

    private void syncEncodedAttemptsToBankCodeIfUnpaid() {
        if (this.paymentStatus != null && this.paymentStatus != PaymentStatus.UNPAID) {
            return;
        }
        if (this.vnpayBankCode != null && !this.vnpayBankCode.isBlank()
                && !this.vnpayBankCode.startsWith(PAY_INIT_DATE_PREFIX)) {
            return;
        }
        if (this.vnpayAttemptHistory.isEmpty()) {
            return;
        }
        String baseCode = resolveCanonicalBaseOrderCode();
        if (baseCode.isEmpty()) {
            return;
        }
        List<String> tokens = new ArrayList<>(this.vnpayAttemptHistory.size());
        for (Map.Entry<String, String> entry : this.vnpayAttemptHistory.entrySet()) {
            String ref = entry.getKey();
            String createDate = entry.getValue();
            if (ref == null || createDate == null || createDate.length() != 14) {
                continue;
            }
            String hhmmss = createDate.substring(8);
            if (ref.equals(baseCode)) {
                tokens.add(hhmmss);
            } else if (ref.startsWith(baseCode + "-")) {
                String suffix = ref.substring(baseCode.length() + 1);
                if (suffix.equals("R" + hhmmss) || suffix.startsWith("R" + hhmmss + "-")) {
                    tokens.add(suffix);
                } else {
                    tokens.add(suffix + "@" + hhmmss);
                }
            }
        }
        if (tokens.isEmpty()) {
            return;
        }
        String encoded = PAY_INIT_DATE_PREFIX + String.join(",", tokens);
        while (encoded.length() > 50 && tokens.size() > 1) {
            tokens.remove(1);
            encoded = PAY_INIT_DATE_PREFIX + String.join(",", tokens);
        }
        if (encoded.length() <= 50) {
            this.vnpayBankCode = encoded;
        }
    }

    private void decodeAttemptsFromBankCodeInto(Map<String, String> target) {
        if (this.vnpayBankCode == null || !this.vnpayBankCode.startsWith(PAY_INIT_DATE_PREFIX)) {
            return;
        }
        String rawTokens = this.vnpayBankCode.substring(PAY_INIT_DATE_PREFIX.length()).trim();
        if (rawTokens.isEmpty()) {
            return;
        }
        String baseCode = resolveCanonicalBaseOrderCode();
        if (baseCode.isEmpty()) {
            return;
        }
        String latestCreate = getVnpayCreateDate();
        String datePrefix = null;
        String latestHhmmss = null;
        if (latestCreate != null && latestCreate.length() == 14) {
            datePrefix = latestCreate.substring(0, 8);
            latestHhmmss = latestCreate.substring(8);
        } else if (getCreatedAt() != null) {
            datePrefix = getCreatedAt().format(DateTimeFormatter.BASIC_ISO_DATE);
        }
        if (datePrefix == null) {
            return;
        }
        String[] parts = rawTokens.split(",");
        for (String rawPart : parts) {
            String token = rawPart.trim();
            if (token.isEmpty()) {
                continue;
            }
            String ref = null;
            String hhmmss = null;
            if (token.length() == 6 && token.chars().allMatch(Character::isDigit)) {
                ref = baseCode;
                hhmmss = token;
            } else if (token.startsWith("R")) {
                int atIdx = token.indexOf('@');
                if (atIdx > 1) {
                    String suffix = token.substring(0, atIdx);
                    hhmmss = token.substring(atIdx + 1);
                    ref = baseCode + "-" + suffix;
                } else if (token.length() >= 7) {
                    ref = baseCode + "-" + token;
                    hhmmss = token.substring(1, 7);
                }
            }
            if (ref != null && hhmmss != null && hhmmss.length() == 6 && hhmmss.chars().allMatch(Character::isDigit)) {
                String resolvedDatePrefix = datePrefix;
                if (latestHhmmss != null && hhmmss.compareTo(latestHhmmss) > 0) {
                    try {
                        resolvedDatePrefix = LocalDate.parse(datePrefix, DateTimeFormatter.BASIC_ISO_DATE)
                                .minusDays(1)
                                .format(DateTimeFormatter.BASIC_ISO_DATE);
                    } catch (Exception ignored) {
                        resolvedDatePrefix = datePrefix;
                    }
                }
                target.putIfAbsent(ref, resolvedDatePrefix + hhmmss);
            }
        }
    }

    private String resolveCanonicalBaseOrderCode() {
        if (this.orderCode != null && !this.orderCode.isBlank()) {
            return this.orderCode.trim();
        }
        if (this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()) {
            String trimmed = this.vnpayTxnRef.trim();
            int retryIdx = trimmed.lastIndexOf("-R");
            if (retryIdx > 0) {
                return trimmed.substring(0, retryIdx);
            }
            return trimmed;
        }
        return "";
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
        CANCELLED,
        RETURNED
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
