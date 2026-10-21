package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByParentId(Long parentId);

    /**
     * Retrieve all active categories directly from database.
     *
     * @return list of active categories
     */
    List<Category> findByIsActiveTrue();

    List<Category> findAllByIsActiveTrueOrderByNameAsc();

    List<Category> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCaseAndParentId(String name, Long parentId);

    boolean existsByNameIgnoreCaseAndParentIsNull(String name);

    boolean existsByParentId(Long parentId);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Product p WHERE p.category.id = :categoryId")
    boolean hasProducts(@Param("categoryId") Long categoryId);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.category.id = :categoryId")
    long countProductsByCategoryId(@Param("categoryId") Long categoryId);

    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.parent ORDER BY c.id ASC")
    List<Category> findAllWithParent();

    /**
     * Retrieve all active categories ordered by name ascending.
     *
     * @return list of active categories sorted by name
     */
    List<Category> findByIsActiveTrueOrderByNameAsc();
}
