-- LOCAL / TEST ONLY. Runs after 02_seed_roles.sql.
-- Opt-in: set the session variables below in THE SAME MySQL connection
-- before running this file. When variables are absent, no account is created.
-- @test_admin_email: a unique email
-- @test_admin_password_hash: a BCrypt cost-12 hash generated outside this file
-- No plaintext password or fixed credential is stored in this repository.
-- Existing accounts are preserved; no password reset or role promotion.
USE aurelia_books_db;

INSERT INTO users (role_id, email, password_hash, full_name, auth_provider, is_active)
SELECT r.id, LOWER(TRIM(@test_admin_email)), @test_admin_password_hash,
       'DDAGTech Test Admin', 'LOCAL', TRUE
FROM roles r
WHERE r.role_name = 'ROLE_ADMIN'
  AND @test_admin_email IS NOT NULL
  AND TRIM(@test_admin_email) <> ''
  AND @test_admin_password_hash REGEXP '^[$]2[aby][$]12[$][./A-Za-z0-9]{53}$'
  AND NOT EXISTS (
      SELECT 1 FROM users u WHERE u.email = LOWER(TRIM(@test_admin_email))
  );
