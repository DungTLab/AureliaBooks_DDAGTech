package com.ddagtech.aureliabooks.service;

/**
 * Port interface for releasing redeemed voucher quotas when an order is validly cancelled
 * due to verified payment failure or expiration (UC11.1 / CRON-01 / BR-03-03 / BR-03-04).
 */
public interface OrderVoucherReleasePort {

    /**
     * Releases any voucher bound to the specified order and decrements its usage counter once
     * within the current database transaction.
     *
     * @param orderId primary key of the cancelled order
     */
    void releaseOrderVoucherIfPresent(Long orderId);
}
