package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

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
    java.util.List<Brand> findAllByOrderByNameAsc();
}
