package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.request.InventoryHistoryFilterRequest;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementResponse;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;

/**
 * UC25 / Task 2: Parameterized read repository querying the {@code inventory_movements} SQL view.
 *
 * Implements strict read-only access aggregated directly from source documents (goods_receipts and orders).
 * Stable paging enforces deterministic sorting across timestamps and composite keys.
 * Absolutely no INSERT, UPDATE, or DELETE API is exposed (BR-01-04).
 */
public interface InventoryMovementRepository {

    /**
     * Legacy read query without entity joins (kept for backward compatibility).
     *
     * @param productId optional product identifier
     * @param actorUserId optional actor identifier
     * @param fromInclusive optional inclusive starting timestamp
     * @param toExclusive optional exclusive ending timestamp
     * @param pageable pagination parameters
     * @return paginated raw summary records
     */
    Page<InventoryMovementSummary> findMovements(Long productId, Long actorUserId,
            LocalDateTime fromInclusive, LocalDateTime toExclusive, Pageable pageable);

    /**
     * Enriched read query over {@code inventory_movements} joined with products, books, and users.
     * Supports keyword search (title, barcode, ISBN, reference code), movement type filtering,
     * actor attribution, date ranges, and stable pagination.
     *
     * @param filter multi-criteria filter parameters
     * @param fromInclusive normalized inclusive start timestamp
     * @param toExclusive normalized exclusive end timestamp
     * @param pageable pagination parameters
     * @return paginated enriched movement records
     */
    Page<InventoryMovementResponse> searchMovements(
            InventoryHistoryFilterRequest filter,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            Pageable pageable
    );
}
