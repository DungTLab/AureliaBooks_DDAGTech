package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Brand;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Publisher;
import org.springframework.data.domain.*;

import java.util.List;

/** UC17/18/19/20. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
public interface MasterDataService {
    Long createAuthor(AuthorForm form);
    void updateAuthor(Long authorId, AuthorForm form);
    void deleteAuthor(Long authorId);
    Long createPublisher(PublisherForm form);
    void updatePublisher(Long publisherId, PublisherForm form);
    void deletePublisher(Long publisherId);
    Long createBrand(BrandForm form);
    void updateBrand(Long brandId, BrandForm form);
    void deleteBrand(Long brandId);
    Long createCategory(CategoryForm form);
    void updateCategory(Long categoryId, CategoryForm form);
    void deleteCategory(Long categoryId);
    // Query methods for UI selection and management
    Page<Author> listAuthors(String keyword, Pageable pageable);
    Author getAuthorById(Long authorId);
    long countBooksByAuthor(Long authorId);
    List<Author> getAllAuthors();
    void toggleAuthorStatus(Long authorId);
    List<Publisher> getAllPublishers();
    List<Brand> getAllBrands();
    List<Category> getAllActiveCategories();
}
