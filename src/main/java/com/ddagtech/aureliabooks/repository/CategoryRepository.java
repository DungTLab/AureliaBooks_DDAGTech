package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * Retrieve all active categories directly from database.
     *
     * @return list of active categories
     */
    List<Category> findByIsActiveTrue();
}
