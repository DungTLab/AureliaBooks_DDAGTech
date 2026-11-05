-- Run once before deploying dual-voucher checkout. Stop checkout/payment writers during this upgrade.
-- MySQL DDL commits implicitly; take a backup before applying to an existing database.
USE aurelia_books_db;
ALTER TABLE order_vouchers ADD COLUMN scope ENUM('PRODUCT', 'SHIPPING') NULL AFTER voucher_id;
UPDATE order_vouchers ov JOIN vouchers v ON v.id = ov.voucher_id SET ov.scope = v.voucher_scope WHERE ov.scope IS NULL;
ALTER TABLE order_vouchers
    MODIFY COLUMN scope ENUM('PRODUCT', 'SHIPPING') NOT NULL,
    ADD CONSTRAINT uk_ov_order_scope UNIQUE (order_id, scope);
ALTER TABLE order_vouchers DROP INDEX uk_ov_order;

-- Preserve promotion details when a cancelled order releases its redemption rows.
ALTER TABLE orders
    ADD COLUMN product_voucher_code VARCHAR(30) NULL,
    ADD COLUMN shipping_voucher_code VARCHAR(30) NULL,
    ADD COLUMN shipping_discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    ADD CONSTRAINT chk_ord_shipping_discount CHECK (shipping_discount_amount >= 0 AND shipping_discount_amount <= shipping_fee);
UPDATE orders o JOIN order_vouchers ov ON ov.order_id=o.id JOIN vouchers v ON v.id=ov.voucher_id
SET o.product_voucher_code=v.code WHERE ov.scope='PRODUCT';
UPDATE orders o JOIN order_vouchers ov ON ov.order_id=o.id JOIN vouchers v ON v.id=ov.voucher_id
SET o.shipping_voucher_code=v.code, o.shipping_discount_amount=ov.discount_applied WHERE ov.scope='SHIPPING';
