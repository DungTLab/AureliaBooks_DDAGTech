package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.GoodsReceiptForm;
import com.ddagtech.aureliabooks.dto.request.QuickCreateProductRequest;
import com.ddagtech.aureliabooks.dto.response.GoodsReceiptDetailDto;
import com.ddagtech.aureliabooks.dto.response.ProductStockAlertDto;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.dto.response.QuickProductResponse;
import com.ddagtech.aureliabooks.dto.response.ReceiptSummary;
import com.ddagtech.aureliabooks.dto.response.StockMetricSummary;
import com.ddagtech.aureliabooks.entity.GoodsReceipt;
import com.ddagtech.aureliabooks.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Set;

/**
 * Service contract for Goods Receipts lifecycle (UC21 Stock Alerts, UC22 Drafts, UC23 Finalization).
 */
public interface GoodsReceiptService {

    /**
     * Creates a new draft goods receipt without mutating inventory stock levels.
     */
    Long createDraft(Long staffId, GoodsReceiptForm form);

    /**
     * Updates an existing draft goods receipt.
     */
    void updateDraft(Long staffId, Long receiptId, GoodsReceiptForm form);

    /**
     * Deletes an existing draft goods receipt.
     */
    void deleteDraft(Long staffId, Long receiptId);

    /**
     * Posts a draft goods receipt into RECEIVED status atomically through StockMutationService.
     */
    void receive(Long managerId, Long receiptId);

    /**
     * Retrieves products at or below the given stock threshold.
     */
    Page<ProductSummary> lowStock(int threshold, Pageable pageable);

    /**
     * Lists goods receipts with optional status and supplier filters.
     */
    Page<ReceiptSummary> getReceipts(GoodsReceipt.Status status, Long supplierId, Pageable pageable);

    /**
     * Retrieves full receipt details including immutable line items and audit metadata.
     */
    GoodsReceiptDetailDto getReceiptDetail(Long receiptId);

    /**
     * Calculates system-wide real-time stock alert metrics (0, 1-10, >10).
     */
    StockMetricSummary getStockMetrics();

    /**
     * Filters catalog items by stock alert thresholds, product type, category, and keyword.
     */
    Page<ProductStockAlertDto> getStockAlerts(
            String keyword,
            Long categoryId,
            Product.ProductType productType,
            String alertLevel,
            Pageable pageable
    );

    /**
     * Quick-creates a new product on-the-fly and makes it immediately available for receipt drafting.
     */
    QuickProductResponse quickCreateProduct(QuickCreateProductRequest request);

    /**
     * Retrieves set of product IDs that are categorized as textbooks.
     */
    Set<Long> getTextbookProductIds();
}
