package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

import java.util.List;

/** UC01/02/03. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
public interface ProductService {
    Page<ProductSummary> browse(ProductFilterRequest filter, Pageable pageable);
    ProductDetailResponse viewDetail(Long productId);
    List<CategorySummary> getActiveCategories();

    List<ProductAutoCompleteResponse> autocomplete(String keyword);
}
