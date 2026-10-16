package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.service.OrderVoucherReleasePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default JDBC-backed implementation of {@link OrderVoucherReleasePort} (III.3.3.a SQL #8).
 * Safely decrements vouchers.used_count and removes order_vouchers mapping within the active transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoOpOrderVoucherReleaseAdapter implements OrderVoucherReleasePort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void releaseOrderVoucherIfPresent(Long orderId) {
        if (orderId == null) {
            return;
        }
        try {
            List<Long> voucherIds = jdbcTemplate.queryForList(
                    "SELECT voucher_id FROM order_vouchers WHERE order_id = ?",
                    Long.class,
                    orderId
            );
            for (Long voucherId : voucherIds) {
                jdbcTemplate.update(
                        "UPDATE vouchers SET used_count = GREATEST(used_count - 1, 0) WHERE id = ?",
                        voucherId
                );
            }
            if (!voucherIds.isEmpty()) {
                jdbcTemplate.update("DELETE FROM order_vouchers WHERE order_id = ?", orderId);
                log.info("Released voucher(s) {} for cancelled order id={}", voucherIds, orderId);
            }
        } catch (Exception ex) {
            log.debug("No order_vouchers table or voucher entry found for order id={}: {}", orderId, ex.getMessage());
        }
    }
}
