package com.ddagtech.aureliabooks.dto.response;

import com.ddagtech.aureliabooks.entity.StockMovementLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * FND-03 Response DTO for displaying immutable stock movement ledger records.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockMovementLogResponse {
    private Long id;
    private Long productId;
    private String productBarcode;
    private String productTitle;
    private String productType;
    private StockMovementLog.TransactionType transactionType;
    private Integer quantityChange;
    private Integer previousStock;
    private Integer currentStock;
    private String referenceCode;
    private Long performedByUserId;
    private String performedByUserName;
    private String note;
    private LocalDateTime createdAt;

    public static StockMovementLogResponse fromEntity(StockMovementLog entity) {
        if (entity == null) {
            return null;
        }
        return StockMovementLogResponse.builder()
                .id(entity.getId())
                .productId(entity.getProduct() != null ? entity.getProduct().getId() : null)
                .productBarcode(entity.getProduct() != null ? entity.getProduct().getBarcode() : null)
                .productTitle(entity.getProduct() != null ? entity.getProduct().getTitle() : null)
                .productType(entity.getProduct() != null && entity.getProduct().getProductType() != null
                        ? entity.getProduct().getProductType().name() : null)
                .transactionType(entity.getTransactionType())
                .quantityChange(entity.getQuantityChange())
                .previousStock(entity.getPreviousStock())
                .currentStock(entity.getCurrentStock())
                .referenceCode(entity.getReferenceCode())
                .performedByUserId(entity.getPerformedBy() != null ? entity.getPerformedBy().getId() : null)
                .performedByUserName(entity.getPerformedBy() != null ? entity.getPerformedBy().getFullName() : "Hệ thống (SYSTEM)")
                .note(entity.getNote())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
