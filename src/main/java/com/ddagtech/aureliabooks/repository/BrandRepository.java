package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    List<Brand> findAllByOrderByNameAsc();

    Page<Brand> findAllByOrderByNameAsc(Pageable pageable);

    Page<Brand> findByNameContainingIgnoreCaseOrderByNameAsc(String keyword, Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM Stationery s WHERE s.brand.id = :brandId")
    boolean hasStationeries(@Param("brandId") Long brandId);

    @Query("SELECT COUNT(s) FROM Stationery s WHERE s.brand.id = :brandId")
    long countStationeriesByBrandId(@Param("brandId") Long brandId);
}
