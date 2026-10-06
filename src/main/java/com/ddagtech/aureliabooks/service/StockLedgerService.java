package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.StockMovementLogResponse;
import com.ddagtech.aureliabooks.entity.StockMovementLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

/**
 * FND-03 Double-entry Stock Ledger Service Engine & Audit Transaction Helper.
 * Owner: Nguyễn Trần Đức Anh.
 * Enforces atomic stock mutations and append-only stock_logs (BR-01-04, BR-04-03, BR-08-02).
 */
public interface StockLedgerService {

    /**
     * Appends an immutable stock log row verifying the invariant:
     * currentStock == previousStock + quantityChange,
     * quantityChange != 0, previousStock >= 0, currentStock >= 0.
     * Note: This method must be executed within the same transaction as product stock mutation.
     */
    void append(Long productId, StockMovementLog.TransactionType type,
            int quantityChange, int previousStock, int currentStock,
            String referenceCode, Long performedByUserId, String note);

    /**
     * Atomically executes stock movement on a product and records immutable ledger log:
     * 1. Acquires pessimistic lock on Product.
     * 2. Validates quantityChange != 0.
     * 3. Calculates currentStock = previousStock + quantityChange.
     * 4. Enforces non-negative stock (currentStock >= 0). Throws AppException(INSUFFICIENT_STOCK) if violated.
     * 5. Updates Product stock_quantity.
     * 6. Inserts immutable StockMovementLog row.
     */
    StockMovementLog processMovement(Long productId, StockMovementLog.TransactionType type,
            int quantityChange, String referenceCode, Long performedByUserId, String note);

    /**
     * Convenience method for IMPORT (inbound stock receipt).
     * Increases stock by quantity (> 0).
     */
    StockMovementLog recordImport(Long productId, int quantity, String referenceCode, Long performedByUserId, String note);

    /**
     * Convenience method for ORDER_DEDUCT (checkout order stock deduction).
     * Decreases stock by quantity (> 0). Fails and rolls back if stock < quantity.
     */
    StockMovementLog recordOrderDeduct(Long productId, int quantity, String referenceCode, Long performedByUserId, String note);

    /**
     * Convenience method for ORDER_CANCELLED_RESTOCK (cancelled order restock).
     * Increases stock by quantity (> 0).
     */
    StockMovementLog recordOrderCancelledRestock(Long productId, int quantity, String referenceCode, Long performedByUserId, String note);

    /**
     * Retrieves paginated stock movement ledger records.
     */
    Page<StockMovementLogResponse> getLedgerLogs(Pageable pageable);

    /**
     * Retrieves paginated stock movement ledger records with multi-criteria filtering.
     */
    Page<StockMovementLogResponse> getLedgerLogsWithFilter(
            StockMovementLog.TransactionType transactionType,
            Long productId,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable);

    /**
     * Retrieves logs associated with a specific business reference code (e.g., GRN code, Order code).
     */
    List<StockMovementLogResponse> getLogsByReferenceCode(String referenceCode);
}
