package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary.MovementType;

import java.time.LocalDateTime;

/**
 * Enriched read-only response model for inventory movement inspection (UC25 / SCR-DB-08).
 * Backed by the inventory_movements SQL view joined with catalog products and staff users.
 * Strictly immutable record; never persist or modify derived ledger records.
 */
public record InventoryMovementResponse(
        Long productId,
        String productTitle,
        String productBarcode,
        MovementType movementType,
        LocalDateTime occurredAt,
        Integer quantityChange,
        String sourceTable,
        Long sourceId,
        Long sourceLineId,
        String referenceCode,
        Long actorUserId,
        String actorName,
        String actorEmail,
        String note
) {
    /**
     * Formats signed integer quantity delta for visual badge (+X or -X).
     *
     * @return signed string representation
     */
    public String formattedQuantityChange() {
        if (quantityChange == null) {
            return "0";
        }
        return quantityChange > 0 ? "+" + quantityChange : String.valueOf(quantityChange);
    }

    /**
     * Checks if movement represents positive stock addition (IMPORT, CANCELLED_RESTOCK, RETURNED_RESTOCK).
     *
     * @return true if quantityChange > 0
     */
    public boolean isInflow() {
        return quantityChange != null && quantityChange > 0;
    }

    /**
     * Checks if movement represents negative stock outflow (ORDER_DEDUCT).
     *
     * @return true if quantityChange < 0
     */
    public boolean isOutflow() {
        return quantityChange != null && quantityChange < 0;
    }

    /**
     * Resolves human-readable actor display name (falling back to email, then "Hệ thống" for automated events).
     *
     * @return user full name, email, or fallback
     */
    public String displayActor() {
        if (actorName != null && !actorName.isBlank()) {
            return actorName;
        }
        if (actorEmail != null && !actorEmail.isBlank()) {
            return actorEmail;
        }
        return "Hệ thống";
    }

    /**
     * Factory mapping method from {@link InventoryMovementSummary} and supplemental catalog/operator details.
     */
    public static InventoryMovementResponse fromSummary(
            InventoryMovementSummary summary,
            String productTitle,
            String productBarcode,
            String actorName,
            String actorEmail,
            String note
    ) {
        if (summary == null) {
            return null;
        }
        return new InventoryMovementResponse(
                summary.productId(),
                productTitle,
                productBarcode,
                summary.movementType(),
                summary.occurredAt(),
                summary.quantityChange(),
                summary.sourceTable(),
                summary.sourceId(),
                summary.sourceLineId(),
                summary.referenceCode(),
                summary.actorUserId(),
                actorName,
                actorEmail,
                note
        );
    }
}
