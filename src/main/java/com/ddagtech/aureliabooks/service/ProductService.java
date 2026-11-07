package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import com.ddagtech.aureliabooks.entity.Product;
import org.springframework.data.domain.*;

import java.util.List;
import java.util.Map;

/** UC01/02/03. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
public interface ProductService {
    Page<ProductSummary> browse(ProductFilterRequest filter, Pageable pageable);
    ProductDetailResponse viewDetail(Long productId);
    List<CategorySummary> getActiveCategories();
    Map<CategorySummary, List<CategorySummary>> groupCategoriesForMenu(List<CategorySummary> categories);
    List<CategorySummary> getCategoriesForType(Product.ProductType type);
    List<AuthorSummary> getAuthors();
    List<PublisherSummary> getPublishers();
    List<BrandSummary> getBrands();

    List<ProductAutoCompleteResponse> autoComplete(String keyword);
}
