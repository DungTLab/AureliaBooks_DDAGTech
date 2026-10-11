package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Stationery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationeryRepository extends JpaRepository<Stationery, Long> {
    /**
     * Loads a stationery item with its product and brand associations to prevent N+1 queries.
     *
     * @param id stationery product ID
     * @return optional stationery with fetched relations
     */
    @org.springframework.data.jpa.repository.Query("SELECT s FROM Stationery s JOIN FETCH s.product p JOIN FETCH s.brand b WHERE s.productId = :id")
    java.util.Optional<Stationery> findByIdWithDetails(@org.springframework.data.repository.query.Param("id") Long id);

    /**
     * Checks if any stationery item is referencing the specified brand.
     *
     * @param brandId brand ID
     * @return true if at least one stationery item references this brand
     */
    @org.springframework.data.jpa.repository.Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM Stationery s WHERE s.brand.id = :brandId")
    boolean existsByBrandId(@org.springframework.data.repository.query.Param("brandId") Long brandId);
}
