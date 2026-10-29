-- Apply to existing databases; initdb scripts only run for a new database.
USE aurelia_books_db;
ALTER TABLE orders MODIFY COLUMN order_status
    ENUM('PENDING_PAYMENT','PENDING_CONFIRMATION','CONFIRMED','SHIPPING','DELIVERED','CANCELLED','RETURNED')
    NOT NULL DEFAULT 'PENDING_PAYMENT';
