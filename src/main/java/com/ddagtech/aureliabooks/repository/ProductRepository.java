package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /** Deduct stock conditionally in the database; accept exactly one updated row, otherwise roll back the entire order. */
    @Modifying(flushAutomatically = true)
    @Query("""
        UPDATE Product p
        SET p.stockQuantity = p.stockQuantity - :quantity
        WHERE p.id = :productId
          AND p.isActive = true
          AND :quantity > 0
          AND p.stockQuantity >= :quantity
    """)
    int deductStockIfAvailable(@Param("productId") Long productId,@Param("quantity") int quantity);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id IN :ids ORDER BY p.id ASC")
    List<Product> findAllByIdInWithLock(@Param("ids") Collection<Long> ids);
}
