package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;
import com.ddagtech.aureliabooks.entity.StockMovementLog;

/** FND-03. Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
public interface StockLedgerService {
    void append(Long productId, StockMovementLog.TransactionType type,
            int quantityChange, int previousStock, int currentStock,
            String referenceCode, Long performedByUserId, String note);
    // TODO: same transaction as stock mutation; no standalone recalculation/update/delete.
}
