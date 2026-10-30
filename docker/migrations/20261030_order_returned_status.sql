-- Apply to existing databases; initdb scripts only run for a new database.
USE aurelia_books_db;
ALTER TABLE orders MODIFY COLUMN order_status
    ENUM('PENDING_PAYMENT','PENDING_CONFIRMATION','CONFIRMED','SHIPPING','DELIVERED','CANCELLED','RETURNED')
    NOT NULL DEFAULT 'PENDING_PAYMENT';

-- The previous full-return flow kept SHIPPING/DELIVERED after recording receipt.
-- Normalize only orders with complete, previously posted return evidence. Include
-- PAID, UNPAID and REFUNDED history; payment eligibility is still checked separately.
-- Re-running is safe: no quantity, payment, milestone or audit row is rewritten.
UPDATE orders
SET order_status = 'RETURNED'
WHERE order_status IN ('SHIPPING', 'DELIVERED')
  AND stock_deducted_at IS NOT NULL
  AND stock_restore_reason = 'RETURNED'
  AND stock_restored_at IS NOT NULL
  AND stock_restored_at >= stock_deducted_at
  AND stock_restored_by_user_id IS NOT NULL;
