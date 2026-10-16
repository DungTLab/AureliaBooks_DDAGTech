package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
