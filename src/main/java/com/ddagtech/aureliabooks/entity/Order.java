package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Duration;
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

    private static final DateTimeFormatter VNPAY_TS_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int BANK_CODE_COLUMN_MAX_LENGTH = 50;

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

    // Retain the promotion snapshot after cancellation releases redemption rows.
    @Column(name = "product_voucher_code", length = 30)
    private String productVoucherCode;

    @Column(name = "shipping_voucher_code", length = 30)
    private String shippingVoucherCode;

    @Column(name = "shipping_discount_amount", precision = 12, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal shippingDiscountAmount = BigDecimal.ZERO;

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
     * Synchronized losslessly with vnpay_bank_code using {@link #PAY_INIT_DATE_PREFIX} while the order is UNPAID.
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

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private long[] settledBloomFilter;

    public record VnpayPaymentAttempt(String txnRef, String createDate) {}

    public void setVnpayTxnRef(String vnpayTxnRef) {
        decodeAttemptsFromBankCodeInto(this.vnpayAttemptHistory);
        if (this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()) {
            String existingCreate = getVnpayCreateDate();
            if (existingCreate != null && !existingCreate.isBlank()) {
                this.vnpayAttemptHistory.putIfAbsent(this.vnpayTxnRef.trim(), existingCreate);
            }
        }
        this.vnpayTxnRef = vnpayTxnRef;
    }

    public String getVnpayBankCode() {
        if (this.vnpayBankCode == null || this.vnpayBankCode.startsWith(PAY_INIT_DATE_PREFIX)) {
            return null;
        }
        int sepIdx = this.vnpayBankCode.indexOf('|');
        if (sepIdx >= 0) {
            String cleanBank = this.vnpayBankCode.substring(0, sepIdx);
            return cleanBank.isEmpty() ? null : cleanBank;
        }
        return this.vnpayBankCode;
    }

    public void setVnpayBankCode(String vnpayBankCode) {
        if (vnpayBankCode == null) {
            this.vnpayBankCode = null;
            return;
        }
        if (vnpayBankCode.startsWith(PAY_INIT_DATE_PREFIX)) {
            this.vnpayBankCode = vnpayBankCode;
            decodeAttemptsFromBankCodeInto(this.vnpayAttemptHistory);
            return;
        }
        if (vnpayBankCode.indexOf('|') >= 0) {
            this.vnpayBankCode = vnpayBankCode;
            decodeSettledAttemptsFromBankCode();
            return;
        }
        decodeSettledAttemptsFromBankCode();
        this.vnpayBankCode = vnpayBankCode;
        syncSettledAttemptsToBankCode();
    }

    /**
     * Exposes the raw persisted column value of vnpay_bank_code for repository/transaction state synchronization.
     */
    public String getPersistedVnpayBankCodeColumn() {
        return this.vnpayBankCode;
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
        decodeAttemptsFromBankCodeInto(this.vnpayAttemptHistory);
        String clean = vnpayCreateDate.trim();
        this.vnpayCreateDate = clean;
        if (this.vnpayPayDate == null || this.vnpayPayDate.isBlank() || this.vnpayPayDate.startsWith(PAY_INIT_DATE_PREFIX)) {
            this.vnpayPayDate = PAY_INIT_DATE_PREFIX + clean;
        }
        if (this.vnpayBankCode != null && this.vnpayBankCode.startsWith(PAY_INIT_DATE_PREFIX)) {
            this.vnpayBankCode = null;
        }
        if (this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()) {
            this.vnpayAttemptHistory.put(this.vnpayTxnRef.trim(), clean);
            syncEncodedAttemptsToBankCodeIfUnpaid();
        }
    }

    /**
     * Checks whether recording the candidate payment attempt fits losslessly within the 50-character
     * vnpay_bank_code column without truncating any prior un-reconciled attempt.
     */
    public boolean canRecordVnpayPaymentAttempt(String txnRef, String createDate) {
        if (txnRef == null || txnRef.isBlank() || createDate == null || createDate.isBlank()) {
            return false;
        }
        Map<String, String> projected = collectMergedAttemptHistory();
        projected.put(txnRef.trim(), createDate.trim());
        String encoded = buildEncodedUnpaidAttempts(projected, createDate.trim());
        return encoded == null || encoded.length() <= BANK_CODE_COLUMN_MAX_LENGTH;
    }

    /**
     * Atomically records a newly initiated VNPay payment attempt (vnp_TxnRef + vnp_CreateDate)
     * while preserving all prior attempts losslessly for multi-attempt QueryDR reconciliation.
     */
    public void recordVnpayPaymentAttempt(String txnRef, String createDate) {
        if (!canRecordVnpayPaymentAttempt(txnRef, createDate)) {
            throw new com.ddagtech.aureliabooks.exception.AppException(
                    com.ddagtech.aureliabooks.constant.ErrorCode.INVALID_INPUT_DATA,
                    "Maximum VNPay payment retry attempts reached; cannot overwrite un-reconciled attempt history."
            );
        }
        decodeAttemptsFromBankCodeInto(this.vnpayAttemptHistory);
        setVnpayTxnRef(txnRef);
        setVnpayCreateDate(createDate);
    }

    /**
     * Returns an immutable chronological list of all initiated VNPay payment attempts for this order.
     */
    public List<VnpayPaymentAttempt> getVnpayPaymentAttempts() {
        Map<String, String> merged = collectMergedAttemptHistory();
        List<VnpayPaymentAttempt> result = new ArrayList<>(merged.size());
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            result.add(new VnpayPaymentAttempt(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    /**
     * Records a settled/confirmed VNPay transaction reference and gateway transaction number
     * both in memory and durably inside the persisted vnpay_bank_code column so that subsequent
     * requests loading a fresh Order entity never re-audit duplicate surplus callbacks.
     */
    public void recordSettledVnpayAttempt(String txnRef, String transactionNo) {
        decodeSettledAttemptsFromBankCode();
        String cleanRef = txnRef != null ? txnRef.trim() : "";
        String cleanTxNo = transactionNo != null ? transactionNo.trim() : "";

        if (this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()) {
            this.settledVnpayTxnRefs.add(encodeSettledRefToken(this.vnpayTxnRef.trim()));
        }
        if (!cleanRef.isEmpty()) {
            this.settledVnpayTxnRefs.add(encodeSettledRefToken(cleanRef));
        }
        if (!cleanTxNo.isEmpty()) {
            this.settledVnpayTransactionNos.add(encodeSettledTxNoToken(cleanTxNo));
        }

        if (this.settledBloomFilter != null) {
            if (!cleanRef.isEmpty()) {
                addStringToBloomFilter(this.settledBloomFilter, cleanRef);
                addStringToBloomFilter(this.settledBloomFilter, encodeSettledRefToken(cleanRef));
            }
            if (!cleanTxNo.isEmpty()) {
                addStringToBloomFilter(this.settledBloomFilter, cleanTxNo);
                addStringToBloomFilter(this.settledBloomFilter, encodeSettledTxNoToken(cleanTxNo));
            }
        }

        syncSettledAttemptsToBankCode();
    }

    /**
     * Determines whether an incoming verified-success IPN represents a distinct successful payment
     * (surplus payment on another retry attempt or gateway transaction) rather than a duplicate callback
     * for an already confirmed transaction, reading from durable persisted column state.
     */
    public boolean isDistinctSuccessfulVnpayAttempt(String txnRef, String transactionNo) {
        decodeSettledAttemptsFromBankCode();

        String cleanRef = txnRef != null ? txnRef.trim() : "";
        String cleanTxNo = transactionNo != null ? transactionNo.trim() : "";
        String incomingRefToken = cleanRef.isEmpty() ? "" : encodeSettledRefToken(cleanRef);
        String incomingTxNoToken = cleanTxNo.isEmpty() ? "" : encodeSettledTxNoToken(cleanTxNo);

        // 1. Exact match against primary order transaction reference (orders.vnpay_txn_ref)
        if (!cleanRef.isEmpty() && this.paymentStatus != PaymentStatus.UNPAID
                && this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()
                && this.vnpayTxnRef.trim().equals(cleanRef)) {
            return false;
        }

        // 2. Exact match against known settled surplus references
        if (!cleanRef.isEmpty() && !this.settledVnpayTxnRefs.isEmpty()) {
            if (this.settledVnpayTxnRefs.contains(cleanRef)
                    || (!incomingRefToken.isEmpty() && this.settledVnpayTxnRefs.contains(incomingRefToken))) {
                return false;
            }
            return true;
        }

        // 3. Exact match against known settled transaction numbers
        if (!cleanTxNo.isEmpty() && !this.settledVnpayTransactionNos.isEmpty()) {
            if (this.settledVnpayTransactionNos.contains(cleanTxNo)
                    || (!incomingTxNoToken.isEmpty() && this.settledVnpayTransactionNos.contains(incomingTxNoToken))) {
                return false;
            }
            return true;
        }

        // 4. Fast verification via Bloom filter when exact in-memory tokens are not available
        if (this.settledBloomFilter != null) {
            boolean refMatch = (!cleanRef.isEmpty() && bloomFilterContains(this.settledBloomFilter, cleanRef))
                    || (!incomingRefToken.isEmpty() && bloomFilterContains(this.settledBloomFilter, incomingRefToken));
            boolean txNoMatch = (!cleanTxNo.isEmpty() && bloomFilterContains(this.settledBloomFilter, cleanTxNo))
                    || (!incomingTxNoToken.isEmpty() && bloomFilterContains(this.settledBloomFilter, incomingTxNoToken));

            if (!cleanRef.isEmpty() && !refMatch) {
                return true;
            }
            if (!cleanTxNo.isEmpty() && !txNoMatch) {
                return true;
            }
            return false;
        }

        // 5. If no settled surplus attempts have ever been recorded, any non-primary reference is distinct
        return !cleanRef.isEmpty() || !cleanTxNo.isEmpty();
    }

    /**
     * Determines whether an incoming payment attempt matches an EXACT known transaction reference
     * or gateway transaction number in persistent order state or in-memory tracking.
     * Guaranteed to have zero false positives (does not rely on probabilistic Bloom filter).
     */
    public boolean hasExactSettledVnpayAttempt(String txnRef, String transactionNo) {
        decodeSettledAttemptsFromBankCode();

        String cleanRef = txnRef != null ? txnRef.trim() : "";
        String cleanTxNo = transactionNo != null ? transactionNo.trim() : "";
        String incomingRefToken = cleanRef.isEmpty() ? "" : encodeSettledRefToken(cleanRef);
        String incomingTxNoToken = cleanTxNo.isEmpty() ? "" : encodeSettledTxNoToken(cleanTxNo);

        // 1. Exact match against primary order transaction reference (orders.vnpay_txn_ref)
        if (!cleanRef.isEmpty() && this.paymentStatus != PaymentStatus.UNPAID
                && this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()
                && this.vnpayTxnRef.trim().equals(cleanRef)) {
            return true;
        }

        // 2. Exact match against known settled reference tokens
        if (!cleanRef.isEmpty() && !this.settledVnpayTxnRefs.isEmpty()) {
            if (this.settledVnpayTxnRefs.contains(cleanRef)
                    || (!incomingRefToken.isEmpty() && this.settledVnpayTxnRefs.contains(incomingRefToken))) {
                return true;
            }
        }

        // 3. Exact match against known settled transaction numbers
        if (!cleanTxNo.isEmpty() && !this.settledVnpayTransactionNos.isEmpty()) {
            if (this.settledVnpayTransactionNos.contains(cleanTxNo)
                    || (!incomingTxNoToken.isEmpty() && this.settledVnpayTransactionNos.contains(incomingTxNoToken))) {
                return true;
            }
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
        if (this.paymentStatus == null || this.paymentStatus == PaymentStatus.UNPAID) {
            syncEncodedAttemptsToBankCodeIfUnpaid();
        } else {
            syncSettledAttemptsToBankCode();
        }
    }

    @PostLoad
    void restoreVnpayInitiationMetadata() {
        if (this.vnpayPayDate != null && this.vnpayPayDate.startsWith(PAY_INIT_DATE_PREFIX)) {
            this.vnpayCreateDate = this.vnpayPayDate.substring(PAY_INIT_DATE_PREFIX.length()).trim();
        }
        decodeAttemptsFromBankCodeInto(this.vnpayAttemptHistory);
        decodeSettledAttemptsFromBankCode();
        if (this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()
                && this.vnpayCreateDate != null && !this.vnpayCreateDate.isBlank()) {
            this.vnpayAttemptHistory.putIfAbsent(this.vnpayTxnRef.trim(), this.vnpayCreateDate);
        }
    }

    private Map<String, String> collectMergedAttemptHistory() {
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
        return merged;
    }

    private void syncEncodedAttemptsToBankCodeIfUnpaid() {
        if (this.paymentStatus != null && this.paymentStatus != PaymentStatus.UNPAID) {
            return;
        }
        if (this.vnpayBankCode != null && !this.vnpayBankCode.isBlank()
                && !this.vnpayBankCode.startsWith(PAY_INIT_DATE_PREFIX)) {
            return;
        }
        Map<String, String> merged = collectMergedAttemptHistory();
        if (merged.isEmpty()) {
            return;
        }
        this.vnpayAttemptHistory.clear();
        this.vnpayAttemptHistory.putAll(merged);

        String latestCreate = getVnpayCreateDate();
        String encoded = buildEncodedUnpaidAttempts(merged, latestCreate);
        if (encoded != null && encoded.length() <= BANK_CODE_COLUMN_MAX_LENGTH) {
            this.vnpayBankCode = encoded;
        }
    }

    private String buildEncodedUnpaidAttempts(Map<String, String> attemptsMap, String latestCreateDate) {
        if (attemptsMap == null || attemptsMap.isEmpty()) {
            return null;
        }
        String baseCode = resolveCanonicalBaseOrderCode();
        if (baseCode.isEmpty()) {
            return null;
        }
        String anchorCreate = latestCreateDate;
        if (anchorCreate == null || anchorCreate.length() != 14) {
            for (String val : attemptsMap.values()) {
                if (val != null && val.length() == 14) {
                    anchorCreate = val;
                }
            }
        }
        if (anchorCreate == null || anchorCreate.length() != 14) {
            return null;
        }

        LocalDateTime latestDt;
        try {
            latestDt = LocalDateTime.parse(anchorCreate, VNPAY_TS_FORMATTER);
        } catch (Exception ex) {
            return null;
        }

        List<String> tokens = new ArrayList<>(attemptsMap.size());
        String prevStandardDelta36 = null;
        int prevStandardCount = 0;

        for (Map.Entry<String, String> entry : attemptsMap.entrySet()) {
            String ref = entry.getKey();
            String createDate = entry.getValue();
            if (ref == null || createDate == null || createDate.length() != 14) {
                continue;
            }
            long deltaSec;
            try {
                LocalDateTime entryDt = LocalDateTime.parse(createDate, VNPAY_TS_FORMATTER);
                deltaSec = Math.max(0L, Duration.between(entryDt, latestDt).getSeconds());
            } catch (Exception ex) {
                continue;
            }
            String delta36 = Long.toString(deltaSec, 36);
            String hhmmss = createDate.substring(8);

            if (ref.equals(baseCode)) {
                prevStandardDelta36 = null;
                prevStandardCount = 0;
                tokens.add("~" + delta36);
            } else if (ref.equals(baseCode + "-R" + hhmmss)) {
                prevStandardDelta36 = delta36;
                prevStandardCount = 1;
                tokens.add(delta36);
            } else if (ref.startsWith(baseCode + "-R" + hhmmss + "-")) {
                String seqStr = ref.substring((baseCode + "-R" + hhmmss + "-").length());
                Integer seq = parsePositiveInt(seqStr);
                if (seq != null && delta36.equals(prevStandardDelta36) && seq == prevStandardCount + 1 && !tokens.isEmpty()) {
                    prevStandardCount = seq;
                    tokens.set(tokens.size() - 1, delta36 + "*" + Integer.toString(prevStandardCount, 36));
                } else if (seq != null) {
                    prevStandardDelta36 = null;
                    prevStandardCount = 0;
                    tokens.add(delta36 + "-" + Integer.toString(seq, 36));
                } else {
                    prevStandardDelta36 = null;
                    prevStandardCount = 0;
                    String suffix = ref.substring(baseCode.length() + 1);
                    tokens.add(suffix + "@" + delta36);
                }
            } else if (ref.startsWith(baseCode + "-")) {
                prevStandardDelta36 = null;
                prevStandardCount = 0;
                String suffix = ref.substring(baseCode.length() + 1);
                tokens.add(suffix + "@" + delta36);
            }
        }

        if (tokens.isEmpty()) {
            return null;
        }
        return PAY_INIT_DATE_PREFIX + String.join(",", tokens);
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
        if (latestCreate == null || latestCreate.length() != 14) {
            return;
        }
        LocalDateTime latestDt;
        try {
            latestDt = LocalDateTime.parse(latestCreate, VNPAY_TS_FORMATTER);
        } catch (Exception ex) {
            return;
        }

        String[] parts = rawTokens.split(",");
        for (String rawPart : parts) {
            String token = rawPart.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                if (token.startsWith("~")) {
                    long deltaSec = Long.parseLong(token.substring(1), 36);
                    String dtStr = latestDt.minusSeconds(deltaSec).format(VNPAY_TS_FORMATTER);
                    target.putIfAbsent(baseCode, dtStr);
                } else if (token.contains("@")) {
                    int atIdx = token.indexOf('@');
                    String suffix = token.substring(0, atIdx);
                    long deltaSec = Long.parseLong(token.substring(atIdx + 1), 36);
                    String dtStr = latestDt.minusSeconds(deltaSec).format(VNPAY_TS_FORMATTER);
                    target.putIfAbsent(baseCode + "-" + suffix, dtStr);
                } else if (token.contains("*")) {
                    int starIdx = token.indexOf('*');
                    long deltaSec = Long.parseLong(token.substring(0, starIdx), 36);
                    int count = Integer.parseInt(token.substring(starIdx + 1), 36);
                    String dtStr = latestDt.minusSeconds(deltaSec).format(VNPAY_TS_FORMATTER);
                    String hhmmss = dtStr.substring(8);
                    target.putIfAbsent(baseCode + "-R" + hhmmss, dtStr);
                    for (int s = 2; s <= count; s++) {
                        target.putIfAbsent(baseCode + "-R" + hhmmss + "-" + s, dtStr);
                    }
                } else if (token.contains("-")) {
                    int dashIdx = token.indexOf('-');
                    long deltaSec = Long.parseLong(token.substring(0, dashIdx), 36);
                    int seq = Integer.parseInt(token.substring(dashIdx + 1), 36);
                    String dtStr = latestDt.minusSeconds(deltaSec).format(VNPAY_TS_FORMATTER);
                    String hhmmss = dtStr.substring(8);
                    target.putIfAbsent(baseCode + "-R" + hhmmss + "-" + seq, dtStr);
                } else {
                    long deltaSec = Long.parseLong(token, 36);
                    String dtStr = latestDt.minusSeconds(deltaSec).format(VNPAY_TS_FORMATTER);
                    String hhmmss = dtStr.substring(8);
                    target.putIfAbsent(baseCode + "-R" + hhmmss, dtStr);
                }
            } catch (Exception ignored) {
                // Ignore malformed token
            }
        }
    }

    private void syncSettledAttemptsToBankCode() {
        if (this.paymentStatus == null || this.paymentStatus == PaymentStatus.UNPAID) {
            return;
        }
        if (this.settledVnpayTxnRefs.isEmpty() && this.settledVnpayTransactionNos.isEmpty() && this.settledBloomFilter == null) {
            return;
        }
        String cleanBank = getVnpayBankCode();
        String bankPrefix = cleanBank != null ? cleanBank : "";

        if (this.settledBloomFilter != null) {
            this.vnpayBankCode = bankPrefix + "|#" + formatBloomFilter(this.settledBloomFilter);
            return;
        }

        String refsPart = String.join(",", this.settledVnpayTxnRefs);
        String txNosPart = String.join(",", this.settledVnpayTransactionNos);
        String candidate = bankPrefix + "|" + refsPart + (txNosPart.isEmpty() ? "" : ";" + txNosPart);
        if (candidate.length() <= BANK_CODE_COLUMN_MAX_LENGTH) {
            this.vnpayBankCode = candidate;
            return;
        }

        // Losslessly transition into 128-bit Bloom filter to prevent truncation across arbitrary retries
        long[] filter = new long[2];
        if (this.vnpayTxnRef != null && !this.vnpayTxnRef.isBlank()) {
            addStringToBloomFilter(filter, this.vnpayTxnRef.trim());
            addStringToBloomFilter(filter, encodeSettledRefToken(this.vnpayTxnRef.trim()));
        }
        for (String r : this.settledVnpayTxnRefs) {
            addStringToBloomFilter(filter, r);
        }
        for (String t : this.settledVnpayTransactionNos) {
            addStringToBloomFilter(filter, t);
        }
        this.settledBloomFilter = filter;
        this.vnpayBankCode = bankPrefix + "|#" + formatBloomFilter(filter);
    }

    private void decodeSettledAttemptsFromBankCode() {
        if (this.vnpayBankCode == null || this.vnpayBankCode.startsWith(PAY_INIT_DATE_PREFIX)) {
            return;
        }
        int sepIdx = this.vnpayBankCode.indexOf('|');
        if (sepIdx < 0 || sepIdx + 1 >= this.vnpayBankCode.length()) {
            return;
        }
        String encoded = this.vnpayBankCode.substring(sepIdx + 1).trim();
        if (encoded.isEmpty()) {
            return;
        }
        if (encoded.startsWith("#")) {
            long[] parsed = parseBloomFilter(encoded.substring(1));
            if (parsed != null) {
                this.settledBloomFilter = parsed;
                return;
            }
        }
        String refsPart = encoded;
        String txNosPart = "";
        int semiIdx = encoded.indexOf(';');
        if (semiIdx >= 0) {
            refsPart = encoded.substring(0, semiIdx);
            txNosPart = encoded.substring(semiIdx + 1);
        }
        for (String r : refsPart.split(",")) {
            if (!r.isBlank()) {
                this.settledVnpayTxnRefs.add(r.trim());
            }
        }
        for (String t : txNosPart.split(",")) {
            if (!t.isBlank()) {
                this.settledVnpayTransactionNos.add(t.trim());
            }
        }
    }

    private static void addStringToBloomFilter(long[] filter, String s) {
        if (s == null || s.isBlank() || filter == null || filter.length < 2) {
            return;
        }
        int h1 = fnv1a32(s);
        int h2 = murmur3_32(s);
        int bit1 = Math.floorMod(h1, 128);
        int bit2 = Math.floorMod(h1 + h2, 128);
        setBloomBit(filter, bit1);
        setBloomBit(filter, bit2);
    }

    private static boolean bloomFilterContains(long[] filter, String s) {
        if (s == null || s.isBlank() || filter == null || filter.length < 2) {
            return false;
        }
        int h1 = fnv1a32(s);
        int h2 = murmur3_32(s);
        int bit1 = Math.floorMod(h1, 128);
        int bit2 = Math.floorMod(h1 + h2, 128);
        return isBloomBitSet(filter, bit1) && isBloomBitSet(filter, bit2);
    }

    private static void setBloomBit(long[] filter, int bitIndex) {
        if (bitIndex < 64) {
            filter[0] |= (1L << bitIndex);
        } else {
            filter[1] |= (1L << (bitIndex - 64));
        }
    }

    private static boolean isBloomBitSet(long[] filter, int bitIndex) {
        if (bitIndex < 64) {
            return (filter[0] & (1L << bitIndex)) != 0;
        } else {
            return (filter[1] & (1L << (bitIndex - 64))) != 0;
        }
    }

    private static String formatBloomFilter(long[] filter) {
        return Long.toUnsignedString(filter[0], 36) + "." + Long.toUnsignedString(filter[1], 36);
    }

    private static long[] parseBloomFilter(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        int dotIdx = str.indexOf('.');
        if (dotIdx < 0) {
            return null;
        }
        try {
            long f0 = Long.parseUnsignedLong(str.substring(0, dotIdx), 36);
            long f1 = Long.parseUnsignedLong(str.substring(dotIdx + 1), 36);
            return new long[] { f0, f1 };
        } catch (Exception ex) {
            return null;
        }
    }

    private static int fnv1a32(String s) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }

    private static int murmur3_32(String s) {
        int hash = 0x9747b28c;
        for (int i = 0; i < s.length(); i++) {
            int k = s.charAt(i);
            k *= 0xcc9e2d51;
            k = Integer.rotateLeft(k, 15);
            k *= 0x1b873593;
            hash ^= k;
            hash = Integer.rotateLeft(hash, 13);
            hash = hash * 5 + 0xe6546b64;
        }
        hash ^= s.length();
        hash ^= (hash >>> 16);
        hash *= 0x85ebca6b;
        hash ^= (hash >>> 13);
        hash *= 0xc2b2ae35;
        hash ^= (hash >>> 16);
        return hash;
    }

    private String encodeSettledRefToken(String txnRef) {
        String baseCode = resolveCanonicalBaseOrderCode();
        if (!baseCode.isEmpty() && txnRef.equals(baseCode)) {
            return "0";
        }
        if (!baseCode.isEmpty() && txnRef.startsWith(baseCode + "-R")) {
            String suffix = txnRef.substring(baseCode.length() + 2);
            if (suffix.length() >= 6 && suffix.substring(0, 6).chars().allMatch(Character::isDigit)) {
                int hhmmss = Integer.parseInt(suffix.substring(0, 6));
                String rest = suffix.substring(6);
                return "R" + Integer.toString(hhmmss, 36) + rest;
            }
        }
        return "H" + compactHash36(txnRef);
    }

    private static String encodeSettledTxNoToken(String txNo) {
        return "T" + compactHash36(txNo);
    }

    private static String compactHash36(String input) {
        int h = 0x811c9dc5;
        for (int i = 0; i < input.length(); i++) {
            h ^= input.charAt(i);
            h *= 0x01000193;
        }
        long unsigned = h & 0xFFFFFFFFL;
        return Long.toString(unsigned % 1679616L, 36);
    }

    private static Integer parsePositiveInt(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        try {
            int val = Integer.parseInt(str);
            return val > 0 ? val : null;
        } catch (NumberFormatException ex) {
            return null;
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
