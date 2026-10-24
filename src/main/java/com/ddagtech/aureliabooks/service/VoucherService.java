package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.VoucherForm;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;
import java.math.BigDecimal;

public interface VoucherService {
    Page<VoucherSummary> list(Long actorId, String keyword, Boolean active, Pageable pageable);
    Long create(Long actorId, VoucherForm form, String clientIp);
    void setActive(Long actorId, Long voucherId, boolean active, String clientIp);

    /** Internal checkout API: amounts must come from the server checkout calculation, never request totals. */
    VoucherQuote preview(Long customerId, String code, BigDecimal merchandiseSubtotal, BigDecimal shippingFee);

    /**
     * Caller must lock products in ascending ID order BEFORE writing order_items (FK shared locks),
     * then create order snapshots, call this and post inventory within ONE transaction.
     */
    VoucherQuote redeem(Long customerId, Long orderId, String code, String clientIp);

    /** Caller must establish an eligible CANCELLED outcome in the same transaction. Replay is a no-op. */
    void releaseCancelledOrder(Long orderId, Long actorId, String clientIp);
}
