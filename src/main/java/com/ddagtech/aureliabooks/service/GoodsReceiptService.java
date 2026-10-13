package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC22/23/21. Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
public interface GoodsReceiptService {
    Long createDraft(Long staffId, GoodsReceiptForm form);
    void updateDraft(Long staffId, Long receiptId, GoodsReceiptForm form);
    void deleteDraft(Long staffId, Long receiptId);
    /** Post once through StockMutationService; persist receiver/time and immutable receipt lines. */
    void receive(Long managerId, Long receiptId);
    Page<ProductSummary> lowStock(int threshold, Pageable pageable);

    /** Lists receipts with optional status and supplier filters. */
    Page<ReceiptSummary> getReceipts(com.ddagtech.aureliabooks.entity.GoodsReceipt.Status status, Long supplierId, Pageable pageable);

    /** Retrieves full receipt details including immutable line items and audit metadata. */
    GoodsReceiptDetailDto getReceiptDetail(Long receiptId);

    /** Calculates system-wide real-time stock alert metrics (0, 1-10, >10). */
    StockMetricSummary getStockMetrics();

    /** Filters catalog items by stock alert thresholds, product type, category, and keyword. */
    Page<ProductStockAlertDto> getStockAlerts(String keyword, Long categoryId, com.ddagtech.aureliabooks.entity.Product.ProductType productType, String alertLevel, Pageable pageable);

    /** Quick-creates a new product on-the-fly and makes it immediately available for receipt drafting. */
    QuickProductResponse quickCreateProduct(QuickCreateProductRequest request);

    /** Retrieves set of product IDs that are categorized as textbooks. */
    java.util.Set<Long> getTextbookProductIds();
}
