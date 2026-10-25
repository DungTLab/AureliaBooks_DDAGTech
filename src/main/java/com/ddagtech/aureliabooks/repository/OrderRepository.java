package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for {@link Order} entities.
 * Provides pessimistic write locks for atomic checkout/IPN/restock mutations and timeout queries.
 */
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    @Override
    @EntityGraph(attributePaths = "user")
    Page<Order> findAll(Specification<Order> spec, Pageable pageable);

    Page<Order> findByUser_Id(Long userId, Pageable pageable);

    Page<Order> findByUser_IdAndOrderStatus(Long userId, Order.OrderStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderCode = :code AND o.user.id = :userId")
    Optional<Order> findOwnedWithLock(@Param("code") String code, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithLock(@Param("id") Long id);

    /** Look up the token before reading the cart to identify an already committed order on repeated submission. */
    Optional<Order> findByOrderCode(String orderCode);

    /** Enforce ownership in the query and fetch items for confirmation mapping within the transaction. */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByOrderCodeAndUser_Id(
            String orderCode,
            Long userId
    );

    /**
     * Acquires a pessimistic write lock on the order matching the VNPay transaction reference or order code.
     *
     * @param txnRef VNPay vnp_TxnRef or order_code
     * @return locked Order if found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.user WHERE o.vnpayTxnRef = :txnRef OR o.orderCode = :txnRef")
    Optional<Order> findByVnpayTxnRefWithLock(@Param("txnRef") String txnRef);

    /**
     * Finds VNPay orders stuck in PENDING_PAYMENT and UNPAID past the 15-minute TTL cutoff (CRON-01).
     *
     * @param cutoffTime expiration cutoff timestamp (now - 15 minutes)
     * @return list of expired pending VNPay orders ordered by creation timestamp ascending
     */
    @Query("SELECT o FROM Order o WHERE o.orderStatus = com.ddagtech.aureliabooks.entity.Order$OrderStatus.PENDING_PAYMENT " +
           "AND o.paymentStatus = com.ddagtech.aureliabooks.entity.Order$PaymentStatus.UNPAID " +
           "AND o.paymentMethod = com.ddagtech.aureliabooks.entity.Order$PaymentMethod.VNPAY " +
           "AND o.createdAt <= :cutoffTime ORDER BY o.createdAt ASC")
    List<Order> findExpiredPendingPaymentOrders(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Finds only the IDs of VNPay orders stuck in PENDING_PAYMENT and UNPAID past the 15-minute TTL cutoff,
     * avoiding premature loading of Order entities into the persistence context before acquiring row locks.
     *
     * @param cutoffTime expiration cutoff timestamp (now - 15 minutes)
     * @return list of expired pending VNPay order IDs
     */
    @Query("SELECT o.id FROM Order o WHERE o.orderStatus = com.ddagtech.aureliabooks.entity.Order$OrderStatus.PENDING_PAYMENT " +
           "AND o.paymentStatus = com.ddagtech.aureliabooks.entity.Order$PaymentStatus.UNPAID " +
           "AND o.paymentMethod = com.ddagtech.aureliabooks.entity.Order$PaymentMethod.VNPAY " +
           "AND o.createdAt <= :cutoffTime ORDER BY o.createdAt ASC")
    List<Long> findExpiredPendingPaymentOrderIds(@Param("cutoffTime") LocalDateTime cutoffTime);
}
