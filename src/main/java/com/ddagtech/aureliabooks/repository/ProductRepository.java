package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.response.StockMetricSummary;
import com.ddagtech.aureliabooks.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /**
     * Checks if a product with the specified barcode exists.
     *
     * @param barcode unique barcode
     * @return true if barcode exists
     */
    boolean existsByBarcodeIgnoreCase(String barcode);

    /**
     * Checks if a product with the specified barcode exists (exact case).
     *
     * @param barcode barcode string
     * @return true if barcode exists
     */
    boolean existsByBarcode(String barcode);

    /**
     * Checks if another product with the specified barcode exists excluding the current ID.
     *
     * @param barcode barcode string
     * @param id      product ID to exclude
     * @return true if another product has this barcode
     */
    boolean existsByBarcodeIgnoreCaseAndIdNot(String barcode, Long id);

    /**
     * Finds all products with category eagerly loaded via EntityGraph.
     * Prevents LazyInitializationException under open-in-view: false and avoids N+1 queries.
     *
     * @param pageable pagination request
     * @return page of products with categories loaded
     */
    @Override
    @EntityGraph(attributePaths = {"category"})
    Page<Product> findAll(Pageable pageable);

    /**
     * Finds a product by ID with its category eagerly loaded.
     *
     * @param id product ID
     * @return optional product with category
     */
    @Override
    @EntityGraph(attributePaths = {"category"})
    Optional<Product> findById(Long id);

    /**
     * Finds products by product type with pagination and category eagerly loaded.
     *
     * @param productType BOOK or STATIONERY
     * @param pageable    pagination request
     * @return page of products with categories loaded
     */
    @EntityGraph(attributePaths = {"category"})
    Page<Product> findByProductType(Product.ProductType productType, Pageable pageable);

    /**
     * Searches products by keyword across title or barcode with pagination and category eagerly loaded.
     *
     * @param title    title keyword
     * @param barcode  barcode keyword
     * @param pageable pagination request
     * @return page of products with categories loaded
     */
    @EntityGraph(attributePaths = {"category"})
    Page<Product> findByTitleContainingIgnoreCaseOrBarcodeContainingIgnoreCase(String title, String barcode, Pageable pageable);

    /**
     * Searches products by keyword and product type with pagination and category eagerly loaded.
     *
     * @param productType BOOK or STATIONERY
     * @param title       title keyword
     * @param barcode     barcode keyword
     * @param pageable    pagination request
     * @return page of products with categories loaded
     */
    @EntityGraph(attributePaths = {"category"})
    @Query("SELECT p FROM Product p WHERE p.productType = :productType AND (LOWER(p.title) LIKE LOWER(CONCAT('%', :title, '%')) OR LOWER(p.barcode) LIKE LOWER(CONCAT('%', :barcode, '%')))")
    Page<Product> findByProductTypeAndKeyword(
            @Param("productType") Product.ProductType productType,
            @Param("title") String title,
            @Param("barcode") String barcode,
            Pageable pageable);

    /**
     * Deduct stock conditionally in the database; accept exactly one updated row, otherwise roll back the entire order.
     *
     * @param productId product ID
     * @param quantity  quantity to deduct
     * @return number of updated rows
     */
    @Modifying(flushAutomatically = true)
    @Query("""
        UPDATE Product p
        SET p.stockQuantity = p.stockQuantity - :quantity
        WHERE p.id = :productId
          AND p.isActive = true
          AND :quantity > 0
          AND p.stockQuantity >= :quantity
    """)
    int deductStockIfAvailable(@Param("productId") Long productId, @Param("quantity") int quantity);

    /**
     * Retrieves a product with a pessimistic write lock for inventory mutation.
     *
     * @param id product ID
     * @return locked product optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithLock(@Param("id") Long id);

    /**
     * Retrieves a product with a pessimistic write lock for inventory update.
     * Alias used across stock ledger engine services.
     *
     * @param id product ID
     * @return locked product optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /**
     * Retrieves multiple products with pessimistic write locks ordered by ID ascending to avoid deadlocks.
     *
     * @param ids collection of product IDs
     * @return list of locked products
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id IN :ids ORDER BY p.id ASC")
    List<Product> findAllByIdInWithLock(@Param("ids") Collection<Long> ids);

    /**
     * Finds a product by its barcode.
     *
     * @param barcode product barcode
     * @return optional product
     */
    Optional<Product> findByBarcode(String barcode);

    Page<Product> findByStockQuantityLessThanEqual(Integer maxStock, Pageable pageable);

    long countByStockQuantityEquals(Integer stockQuantity);

    long countByStockQuantityBetween(Integer minStock, Integer maxStock);

    long countByStockQuantityGreaterThan(Integer minStock);

    @EntityGraph(attributePaths = {"category"})
    List<Product> findByIsActiveTrueOrderByTitleAsc();

    @Override
    @EntityGraph(attributePaths = {"category"})
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

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
    StockMetricSummary getActiveStockMetrics();

    /**
     * Retrieves all active products that have a non-null semantic embedding JSON vector
     * for preloading into the in-memory vector store (CRON-02 / FE-6).
     *
     * @return list of active products with embeddings
     */
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.embedding IS NOT NULL")
    List<Product> findActiveProductsWithEmbedding();
    @Query("select p from Product p join p.category c where p.id in :ids and p.isActive = true and c.isActive = true and p.stockQuantity > 0")
    List<Product> findRecommendationCandidates(@Param("ids") Collection<Long> ids);

    @Query("select p from Product p join p.category c where p.isActive = true and c.isActive = true and p.stockQuantity > 0 and (:categoryId is null or c.id = :categoryId) order by p.id desc")
    List<Product> findRecommendationFallback(@Param("categoryId") Long categoryId, Pageable pageable);
}
