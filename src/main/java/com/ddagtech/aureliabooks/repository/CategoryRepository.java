package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Category;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * Locks a category row exclusively (PESSIMISTIC_WRITE) to prevent race conditions during updates.
     *
     * @param id category ID
     * @return locked category entity
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Category c WHERE c.id = :id")
    Optional<Category> findByIdWithLock(@Param("id") Long id);

    /**
     * Locks all root categories exclusively (PESSIMISTIC_WRITE) to serialize root category modifications.
     *
     * @return list of locked root categories
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Category c WHERE c.parent IS NULL")
    List<Category> findRootCategoriesWithLock();

    List<Category> findByParentId(Long parentId);

    /**
     * Retrieve all active categories directly from database.
     *
     * @return list of active categories
     */
    List<Category> findByIsActiveTrue();

    /**
     * Retrieve all active categories ordered by name ascending.
     *
     * @return list of active categories sorted by name
     */
    List<Category> findByIsActiveTrueOrderByNameAsc();

    /**
     * Returns all active categories ordered by name ascending.
     *
     * @return list of active categories
     */
    List<Category> findAllByIsActiveTrueOrderByNameAsc();

    List<Category> findAllByOrderByNameAsc();

    /**
     * Checks if a category name exists under a specific parent category.
     *
     * @param name     category name
     * @param parentId parent category ID
     * @return true if category exists under parent
     */
    boolean existsByNameIgnoreCaseAndParentId(String name, Long parentId);

    /**
     * Checks if another category exists under the same parent with the same name.
     *
     * @param name     category name
     * @param parentId parent category ID
     * @param id       category ID to exclude
     * @return true if another category exists with this name under the parent
     */
    boolean existsByNameIgnoreCaseAndParentIdAndIdNot(String name, Long parentId, Long id);

    /**
     * Checks if a root category with the given name exists.
     *
     * @param name category name
     * @return true if root category exists
     */
    boolean existsByNameIgnoreCaseAndParentIsNull(String name);

    /**
     * Checks if another root category with the given name exists excluding current ID.
     *
     * @param name category name
     * @param id   category ID to exclude
     * @return true if another root category exists with this name
     */
    boolean existsByNameIgnoreCaseAndParentIsNullAndIdNot(String name, Long id);

    /**
     * Checks if any subcategories exist under the specified parent ID.
     *
     * @param parentId parent ID
     * @return true if child categories exist
     */
    boolean existsByParentId(Long parentId);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Product p WHERE p.category.id = :categoryId")
    boolean hasProducts(@Param("categoryId") Long categoryId);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.category.id = :categoryId")
    long countProductsByCategoryId(@Param("categoryId") Long categoryId);

    @Query("SELECT c FROM Category c LEFT JOIN FETCH c.parent ORDER BY c.id ASC")
    List<Category> findAllWithParent();
}
