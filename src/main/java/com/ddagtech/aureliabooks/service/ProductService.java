package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.AuthorSummary;
import com.ddagtech.aureliabooks.dto.response.BrandSummary;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.ProductAutoCompleteResponse;
import com.ddagtech.aureliabooks.dto.response.ProductDetailResponse;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.dto.response.PublisherSummary;
import com.ddagtech.aureliabooks.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

/** Provides storefront product browsing, search, and detail queries. */
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
