package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** UC25 scaffold. Manager/Admin read document-derived movements; no manual writes. */
public interface InventoryHistoryService {
    Page<InventoryMovementSummary> history(Long productId, Long actorUserId,
            LocalDateTime fromInclusive, LocalDateTime toExclusive, Pageable pageable);
}
