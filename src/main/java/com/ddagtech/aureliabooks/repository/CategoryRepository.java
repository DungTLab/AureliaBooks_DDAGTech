package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
    public List<Category> findByParentId(Long parentId);
}
