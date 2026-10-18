-- Existing LOCAL database upgrade only. Fresh installs use docker/initdb/01_schema.sql.
-- Preserve accounts and default old contact flags to FALSE; do not invent ownership proof.
USE aurelia_books_db;
ALTER TABLE users MODIFY COLUMN email VARCHAR(100) NULL;
SET @account_email_flag = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='email_verified')=0,
 'ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE AFTER phone','SELECT 1');
PREPARE account_upgrade FROM @account_email_flag; EXECUTE account_upgrade; DEALLOCATE PREPARE account_upgrade;
SET @account_phone_flag = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='phone_verified')=0,
 'ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE AFTER email_verified','SELECT 1');
PREPARE account_upgrade FROM @account_phone_flag; EXECUTE account_upgrade; DEALLOCATE PREPARE account_upgrade;
-- Fails safely if pre-existing subjects are duplicated across auth_provider values.
ALTER TABLE users DROP INDEX uk_users_provider, ADD CONSTRAINT uk_users_provider UNIQUE(provider_id);
