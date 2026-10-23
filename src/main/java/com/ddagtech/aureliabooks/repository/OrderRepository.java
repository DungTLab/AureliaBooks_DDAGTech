package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

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
}
