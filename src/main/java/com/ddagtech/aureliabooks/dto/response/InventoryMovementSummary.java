package com.ddagtech.aureliabooks.dto.response;

import java.time.LocalDateTime;

/** UC25 read model from inventory_movements; never persist or modify these derived rows. */
public record InventoryMovementSummary(
        Long productId,
        MovementType movementType,
        LocalDateTime occurredAt,
        Integer quantityChange,
        String sourceTable,
        Long sourceId,
        Long sourceLineId,
        String referenceCode,
        Long actorUserId) {
    public enum MovementType {
        IMPORT, ORDER_DEDUCT, ORDER_CANCELLED_RESTOCK, ORDER_RETURNED_RESTOCK
    }
}
