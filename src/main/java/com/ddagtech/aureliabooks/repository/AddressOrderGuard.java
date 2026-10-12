package com.ddagtech.aureliabooks.repository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AddressOrderGuard {
    private final JdbcTemplate jdbc;
    public boolean hasUnfinishedOrder(Long ownerId, Long addressId) {
        // NULL references are legacy orders: conservatively protect addresses until these orders finish.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM orders WHERE user_id = ?
              AND order_status NOT IN ('DELIVERED', 'CANCELLED')
              AND (shipping_address_id = ? OR shipping_address_id IS NULL))
            """, Boolean.class, ownerId, addressId));
    }
}
