package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/**
 * Service contract for catalog administration (UC15: Books, UC16: Stationery).
 * Enforces business invariants such as read-only stock quantities.
 */
public interface AdminProductService {
    Long createBook(BookForm form);
    void updateBook(Long productId, BookForm form);
    BookForm getBookForEdit(Long productId);

    Long createStationery(StationeryForm form);
    void updateStationery(Long productId, StationeryForm form);
    StationeryForm getStationeryForEdit(Long productId);

    void deactivate(Long productId);
    void toggleActive(Long productId);

    org.springframework.data.domain.Page<com.ddagtech.aureliabooks.entity.Product> listProducts(
            com.ddagtech.aureliabooks.entity.Product.ProductType productType,
            String keyword,
            org.springframework.data.domain.Pageable pageable);

    com.ddagtech.aureliabooks.entity.Product getProductById(Long productId);
}
