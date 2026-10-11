package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
     * Finds products by product type with pagination.
     *
     * @param productType BOOK or STATIONERY
     * @param pageable    pagination request
     * @return page of products
     */
    org.springframework.data.domain.Page<Product> findByProductType(Product.ProductType productType, org.springframework.data.domain.Pageable pageable);

    /**
     * Searches products by keyword across title or barcode with pagination.
     *
     * @param title    title keyword
     * @param barcode  barcode keyword
     * @param pageable pagination request
     * @return page of products
     */
    org.springframework.data.domain.Page<Product> findByTitleContainingIgnoreCaseOrBarcodeContainingIgnoreCase(String title, String barcode, org.springframework.data.domain.Pageable pageable);

    /**
     * Searches products by keyword and product type with pagination.
     *
     * @param productType BOOK or STATIONERY
     * @param title       title keyword
     * @param barcode     barcode keyword
     * @param pageable    pagination request
     * @return page of products
     */
    @org.springframework.data.jpa.repository.Query("SELECT p FROM Product p WHERE p.productType = :productType AND (LOWER(p.title) LIKE LOWER(CONCAT('%', :title, '%')) OR LOWER(p.barcode) LIKE LOWER(CONCAT('%', :barcode, '%')))")
    org.springframework.data.domain.Page<Product> findByProductTypeAndKeyword(
            @org.springframework.data.repository.query.Param("productType") Product.ProductType productType,
            @org.springframework.data.repository.query.Param("title") String title,
            @org.springframework.data.repository.query.Param("barcode") String barcode,
            org.springframework.data.domain.Pageable pageable);
}
