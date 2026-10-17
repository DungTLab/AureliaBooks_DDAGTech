package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    /**
     * Retrieve subcategories by parent category ID for category tree navigation.
     *
     * @param parentId ID of the parent category
     * @return list of child categories
     */
    List<Category> findByParentId(Long parentId);

    /**
     * Retrieve all active categories directly from database.
     *
     * @return list of active categories
     */
    List<Category> findByIsActiveTrue();

    /**
     * Returns all active categories ordered by name ascending.
     *
     * @return list of active categories
     */
    List<Category> findAllByIsActiveTrueOrderByNameAsc();

    /**
     * Checks if a category name exists under a specific parent category.
     *
     * @param name     category name
     * @param parentId parent category ID
     * @return true if category exists under parent
     */
    boolean existsByNameIgnoreCaseAndParentId(String name, Long parentId);

    /**
     * Checks if a root category with the given name exists.
     *
     * @param name category name
     * @return true if root category exists
     */
    boolean existsByNameIgnoreCaseAndParentIsNull(String name);

    /**
     * Checks if any subcategories exist under the specified parent ID.
     *
     * @param parentId parent ID
     * @return true if child categories exist
     */
    boolean existsByParentId(Long parentId);

    /**
     * Verifies if any product is associated with the given category.
     *
     * @param categoryId category ID
     * @return true if at least one product references this category
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Product p WHERE p.category.id = :categoryId")
    boolean hasProducts(@Param("categoryId") Long categoryId);

    List<Category> findAllByOrderByNameAsc();

    @Query("SELECT COUNT(p) FROM Product p WHERE p.category.id = :categoryId")
    long countProductsByCategoryId(@Param("categoryId") Long categoryId);

    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.parent ORDER BY c.id ASC")
    List<Category> findAllWithParent();
}
