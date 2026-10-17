package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.AuthorForm;
import com.ddagtech.aureliabooks.dto.request.BrandForm;
import com.ddagtech.aureliabooks.dto.request.CategoryForm;
import com.ddagtech.aureliabooks.dto.request.PublisherForm;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Brand;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Publisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Service contract for managing Master Taxonomies (UC17: Authors, UC18: Publishers, UC19: Brands, UC20: Categories).
 * Maintained by Catalog Specialist (Huỳnh Nhật Duy - husky).
 */
public interface MasterDataService {

    // =========================================================================
    // UC17: AUTHORS MANAGEMENT (SCR-M03a)
    // =========================================================================
    Long createAuthor(AuthorForm form);
    void updateAuthor(Long authorId, AuthorForm form);
    void deleteAuthor(Long authorId);
    Page<Author> listAuthors(String keyword, Pageable pageable);
    Author getAuthorById(Long authorId);
    long countBooksByAuthor(Long authorId);
    List<Author> getAllAuthors();

    // =========================================================================
    // UC18: PUBLISHERS MANAGEMENT (SCR-M03b)
    // =========================================================================
    Long createPublisher(PublisherForm form);
    void updatePublisher(Long publisherId, PublisherForm form);
    void deletePublisher(Long publisherId);
    Page<Publisher> listPublishers(String keyword, Pageable pageable);
    Publisher getPublisherById(Long publisherId);
    long countBooksByPublisher(Long publisherId);
    List<Publisher> getAllPublishers();

    // =========================================================================
    // UC19: BRANDS MANAGEMENT (SCR-M03c)
    // =========================================================================
    Long createBrand(BrandForm form);
    void updateBrand(Long brandId, BrandForm form);
    void deleteBrand(Long brandId);
    Page<Brand> listBrands(String keyword, Pageable pageable);
    Brand getBrandById(Long brandId);
    long countStationeriesByBrand(Long brandId);
    List<Brand> getAllBrands();

    // =========================================================================
    // UC20: CATEGORIES MANAGEMENT (SCR-M03d)
    // =========================================================================
    Long createCategory(CategoryForm form);
    void updateCategory(Long categoryId, CategoryForm form);
    void deleteCategory(Long categoryId);
    List<Category> getAllCategoriesWithParent();
    List<Category> getAllActiveCategories();
    long countProductsByCategory(Long categoryId);
    void toggleCategoryStatus(Long categoryId);
}
