package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

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
    // TODO: typed list/detail DTOs, uniqueness, parent-cycle and reference checks.
}
