package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.InventoryHistoryFilterRequest;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementResponse;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;

/**
 * Service managing read-only inspection of inventory movement history (UC25 / SCR-DB-08).
 * Derived strictly from source documents (goods receipts and sales orders); manual writes are prohibited.
 */
public interface InventoryHistoryService {

    /**
     * Legacy raw view query method (kept for backward compatibility).
     */
    Page<InventoryMovementSummary> history(Long productId, Long actorUserId,
            LocalDateTime fromInclusive, LocalDateTime toExclusive, Pageable pageable);

    /**
     * Queries enriched inventory movement history with multi-criteria filtering,
     * date boundary normalization, and pagination sanitization (UC25).
     *
     * @param filter multi-field filter request criteria
     * @param pageable pagination parameters (page, size)
     * @return paginated enriched movement records
     */
    Page<InventoryMovementResponse> getInventoryHistory(
            InventoryHistoryFilterRequest filter,
            Pageable pageable
    );
}
