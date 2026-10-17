package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BrandRepository extends JpaRepository<Brand, Long> {
    /**
     * Checks if a brand with the given name exists.
     *
     * @param name brand name
     * @return true if brand exists
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Checks if another brand with the given name exists excluding current ID.
     *
     * @param name brand name
     * @param id   brand ID to exclude
     * @return true if another brand exists
     */
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    /**
     * Returns all brands ordered by name ascending for dropdown selection.
     *
     * @return list of brands
     */
    List<Brand> findAllByOrderByNameAsc();

    Page<Brand> findByNameContainingIgnoreCaseOrderByNameAsc(String keyword, Pageable pageable);
    Page<Brand> findAllByOrderByNameAsc(Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM Stationery s WHERE s.brand.id = :brandId")
    boolean hasStationeries(@Param("brandId") Long brandId);

    @Query("SELECT COUNT(s) FROM Stationery s WHERE s.brand.id = :brandId")
    long countStationeriesByBrandId(@Param("brandId") Long brandId);
}
