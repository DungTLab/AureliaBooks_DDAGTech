package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithLock(@Param("id") Long id);

    /**
     * Eagerly loads an order with its items and products in a single SQL query.
     * Eliminates N+1 query problem when viewing order detail modal (L-NEW-02).
     *
     * @param id order entity ID
     * @return Optional containing the order with items and products fetched
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items oi LEFT JOIN FETCH oi.product WHERE o.id = :id")
    Optional<Order> findByIdWithItemsAndProduct(@Param("id") Long id);

    /** Look up the token before reading the cart to identify an already committed order on repeated submission. */
    Optional<Order> findByOrderCode(String orderCode);

    /** Enforce ownership in the query and fetch items for confirmation mapping within the transaction. */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByOrderCodeAndUser_Id(
            String orderCode,
            Long userId
    );
}
