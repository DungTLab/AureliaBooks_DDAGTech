package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.OrderVoucher;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface OrderVoucherRepository extends JpaRepository<OrderVoucher, Long> {
    boolean existsByUserIdAndVoucherId(Long userId, Long voucherId);

    @EntityGraph(attributePaths = "voucher")
    Optional<OrderVoucher> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ov from OrderVoucher ov where ov.order.id = :orderId")
    Optional<OrderVoucher> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Query("select ov.voucher.id as voucherId, count(ov) as redemptions from OrderVoucher ov where ov.voucher.id in :ids group by ov.voucher.id")
    List<UsageCount> countRedemptions(@Param("ids") Collection<Long> ids);

    interface UsageCount {
        Long getVoucherId();
        long getRedemptions();
    }
}
