package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

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

    /**
     * Retrieves all active products that have a non-null semantic embedding JSON vector
     * for preloading into the in-memory vector store (CRON-02 / FE-6).
     *
     * @return list of active products with embeddings
     */
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.embedding IS NOT NULL")
    List<Product> findActiveProductsWithEmbedding();
}
