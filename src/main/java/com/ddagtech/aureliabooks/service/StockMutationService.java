package com.ddagtech.aureliabooks.service;

/**
 * FND-03. Owner: Nguyen Tran Duc Anh. Contract for document-based inventory posting.
 * Implementation belongs to the inventory/order sprint tasks; this is a scaffold.
 * Each operation must lock/check the source document, mutate every line atomically,
 * stamp its posting metadata and audit in the same transaction. Replays are no-ops.
 * No standalone quantity adjustment is supported. Posted line quantities are immutable.
 */
public interface StockMutationService {
    /** Manager posts a draft receipt; stamps received_at and received_by_user_id. */
    void receiveReceipt(Long receiptId, Long managerId);

    /** Deduct the immutable order lines once; stamp stock_deducted_at, reject insufficient stock. */
    void deductOrder(Long orderId);

    /** Restore only if this order actually deducted stock and has not already restored it. */
    void restoreCancelledOrder(Long orderId, Long actorUserId);

    /** Staff confirms physical receipt of a successful full return; restore immediately, once. */
    void restoreReturnedOrder(Long orderId, Long staffId);
}
