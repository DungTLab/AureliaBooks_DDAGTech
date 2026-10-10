-- Apply once to an existing MySQL 8 database, after a backup. No data is deleted.
-- Fresh databases already have these keys in docker/initdb/01_schema.sql.
ALTER TABLE shipping_addresses ADD CONSTRAINT uk_addr_id_user UNIQUE (id, user_id);
ALTER TABLE orders
  ADD COLUMN shipping_address_id BIGINT NULL COMMENT 'UC09 source address; nullable only for legacy orders' AFTER user_id,
  ADD CONSTRAINT fk_ord_address_owner FOREIGN KEY (shipping_address_id, user_id)
    REFERENCES shipping_addresses(id, user_id) ON DELETE RESTRICT;
-- Do not infer IDs from text snapshots. NULL legacy references are conservatively protected
-- by AddressOrderGuard until DELIVERED/CANCELLED. Checkout must set the owned active ID.
