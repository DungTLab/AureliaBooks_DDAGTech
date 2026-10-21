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

    org.springframework.data.domain.Page<Product> findByStockQuantityLessThanEqual(Integer maxStock, org.springframework.data.domain.Pageable pageable);

    long countByStockQuantityEquals(Integer stockQuantity);

    long countByStockQuantityBetween(Integer minStock, Integer maxStock);

    long countByStockQuantityGreaterThan(Integer minStock);

    @EntityGraph(attributePaths = {"category"})
    List<Product> findByIsActiveTrueOrderByTitleAsc();

    @Override
    @EntityGraph(attributePaths = {"category"})
    org.springframework.data.domain.Page<Product> findAll(org.springframework.data.jpa.domain.Specification<Product> spec, org.springframework.data.domain.Pageable pageable);

    @Query("""
        SELECT new com.ddagtech.aureliabooks.dto.response.StockMetricSummary(
            COUNT(p),
            COALESCE(SUM(CASE WHEN p.stockQuantity <= 0 THEN 1L ELSE 0L END), 0L),
            COALESCE(SUM(CASE WHEN p.stockQuantity >= 1 AND p.stockQuantity <= 10 THEN 1L ELSE 0L END), 0L),
            COALESCE(SUM(CASE WHEN p.stockQuantity > 10 THEN 1L ELSE 0L END), 0L)
        )
        FROM Product p
        WHERE p.isActive = true
    """)
    com.ddagtech.aureliabooks.dto.response.StockMetricSummary getActiveStockMetrics();

    boolean existsByBarcode(String barcode);
}
