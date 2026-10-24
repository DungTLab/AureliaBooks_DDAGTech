-- Upgrade an existing database without rerunning the destructive bootstrap schema.
-- Safe to run again; does not rewrite existing users or address values.
USE aurelia_books_db;

ALTER TABLE shipping_addresses
    MODIFY district VARCHAR(100) NULL COMMENT 'Legacy district only; new two-tier addresses leave this empty';

-- A Google customer may detach the old identity after verifying a replacement email.
-- The new Google subject is not known until the next Google sign-in.
ALTER TABLE users DROP CHECK chk_auth_credentials,
    ADD CONSTRAINT chk_auth_credentials CHECK (
        (auth_provider = 'LOCAL' AND password_hash IS NOT NULL)
        OR
        (auth_provider = 'GOOGLE' AND (provider_id IS NOT NULL OR (email_verified = TRUE AND email IS NOT NULL)))
    );
