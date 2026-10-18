package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import com.ddagtech.aureliabooks.repository.InventoryMovementRepository;
import com.ddagtech.aureliabooks.service.InventoryHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Read-only UC25 service implementation backed by the {@code inventory_movements} SQL view.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryHistoryServiceImpl implements InventoryHistoryService {

    private final InventoryMovementRepository inventoryMovementRepository;

    @Override
    public Page<InventoryMovementSummary> history(
            Long productId, Long actorUserId,
            LocalDateTime fromInclusive, LocalDateTime toExclusive, Pageable pageable) {
        return inventoryMovementRepository.findMovements(
                productId, actorUserId, fromInclusive, toExclusive, pageable);
    }
}
