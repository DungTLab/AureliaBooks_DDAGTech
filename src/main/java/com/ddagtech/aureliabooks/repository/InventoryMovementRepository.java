package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * UC25/UC27 scaffold. Implement parameterized read queries over inventory_movements.
 * Filters use [fromInclusive, toExclusive). Stable paging uses occurred_at, source_table,
 * source_id, source_line_id and movement_type. No INSERT/UPDATE/DELETE API is exposed.
 */
public interface InventoryMovementRepository {
    Page<InventoryMovementSummary> findMovements(Long productId, Long actorUserId,
            LocalDateTime fromInclusive, LocalDateTime toExclusive, Pageable pageable);
}
