package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Stationery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StationeryRepository extends JpaRepository<Stationery, Long> {
    /**
     * Loads a stationery item with its product and brand associations to prevent N+1 queries.
     *
     * @param id stationery product ID
     * @return optional stationery with fetched relations
     */
    @Query("SELECT s FROM Stationery s JOIN FETCH s.product p JOIN FETCH p.category cat JOIN FETCH s.brand b WHERE s.productId = :id")
    Optional<Stationery> findByIdWithDetails(@Param("id") Long id);

    /**
     * Checks if any stationery item is referencing the specified brand.
     *
     * @param brandId brand ID
     * @return true if at least one stationery item references this brand
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM Stationery s WHERE s.brand.id = :brandId")
    boolean existsByBrandId(@Param("brandId") Long brandId);
}
