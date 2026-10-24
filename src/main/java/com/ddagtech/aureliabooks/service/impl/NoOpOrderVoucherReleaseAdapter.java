package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.service.OrderVoucherReleasePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default JDBC-backed implementation of {@link OrderVoucherReleasePort} (V2.4 §5.2, §5.3).
 * Safely deletes the order_vouchers redemption row and restores vouchers.usage_limit by 1
 * within the active cancellation transaction.
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
            if (voucherIds.isEmpty()) {
                return;
            }
            int deletedRows = jdbcTemplate.update(
                    "DELETE FROM order_vouchers WHERE order_id = ?",
                    orderId
            );
            if (deletedRows > 0) {
                for (Long voucherId : voucherIds) {
                    jdbcTemplate.update(
                            "UPDATE vouchers SET usage_limit = usage_limit + 1 WHERE id = ?",
                            voucherId
                    );
                }
                log.info("Released voucher(s) {} and restored usage_limit for cancelled order id={}", voucherIds, orderId);
            }
        } catch (Exception ex) {
            log.debug("No order_vouchers entry released for order id={}: {}", orderId, ex.getMessage());
        }
    }
}
