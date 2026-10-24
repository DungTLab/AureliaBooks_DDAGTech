package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.service.OrderVoucherReleasePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default JDBC-backed implementation of {@link OrderVoucherReleasePort} (V2.4 §5.2, §5.3).
 * Deletes the order_vouchers redemption row and restores vouchers.usage_limit by 1
 * within the active cancellation transaction. Any SQL/data exception propagates
 * so the enclosing transaction rolls back atomically.
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
        List<Long> voucherIds = jdbcTemplate.queryForList(
                "SELECT voucher_id FROM order_vouchers WHERE order_id = ?",
                Long.class,
                orderId
        );
        if (voucherIds == null || voucherIds.isEmpty()) {
            return;
        }
        int deletedRows = jdbcTemplate.update(
                "DELETE FROM order_vouchers WHERE order_id = ?",
                orderId
        );
        if (deletedRows > 0) {
            for (Long voucherId : voucherIds) {
                int updatedRows = jdbcTemplate.update(
                        "UPDATE vouchers SET usage_limit = usage_limit + 1 WHERE id = ?",
                        voucherId
                );
                if (updatedRows != 1) {
                    throw new IllegalStateException(
                            "Failed to restore usage_limit for voucher id=" + voucherId + " on cancelled order id=" + orderId
                    );
                }
            }
            log.info("Released voucher(s) {} and restored usage_limit for cancelled order id={}", voucherIds, orderId);
        }
    }
}
