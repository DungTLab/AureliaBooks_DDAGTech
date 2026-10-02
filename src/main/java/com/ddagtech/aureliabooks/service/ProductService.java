package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC01/02/03. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
public interface ProductService {
    Page<ProductSummary> browse(ProductFilterRequest filter, Pageable pageable);
    ProductSummary viewDetail(Long productId);
    // TODO: add Book/Stationery detail response contracts without exposing entities.
}
