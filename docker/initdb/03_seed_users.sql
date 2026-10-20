-- =============================================================================
-- SYSTEM SEED USERS FOR AURELIABOOK (SWP391)
-- All accounts from docs/accounts/ACCOUNTS.md
-- =============================================================================
SET NAMES 'utf8mb4';
SET CHARACTER SET utf8mb4;

USE aurelia_books_db;

-- BCrypt password hashes (cost factor = 12):
-- Admin@123    -> $2a$12$MyjwLKg2SE5PUCbaEZlei.wuhiouXbtTgstqcWAitLKG9vnl6nXUm
-- Manager@123  -> $2a$12$svqPVfiYIVljhmiwl6oZ0eiYbZ4cU1hw7tBwywLbgkX4oa4I2ynH2
-- Staff@123    -> $2a$12$fvtIT61vJL0mXABLf9Ss8Opoy8hZh90nxZ4mzcRVXnkV1zIrWPeRK
-- Customer@123 -> $2a$12$ANd2LdYem20MpRFNMoDd1eJD.1dWA6xNF/Eg3M54pxcraU4VhIIMW

INSERT INTO users (role_id, email, password_hash, full_name, phone, email_verified, phone_verified, dob, gender, auth_provider, is_active)
VALUES
-- 1. Main RBAC Accounts
(1, 'admin@aureliabook.vn', '$2a$12$MyjwLKg2SE5PUCbaEZlei.wuhiouXbtTgstqcWAitLKG9vnl6nXUm', 'Quản Trị Viên (Admin)', '0988888888', TRUE, TRUE, '1990-01-01', 'MALE', 'LOCAL', TRUE),
(2, 'manager@aureliabook.vn', '$2a$12$svqPVfiYIVljhmiwl6oZ0eiYbZ4cU1hw7tBwywLbgkX4oa4I2ynH2', 'Nguyễn Quản Lý (Manager)', '0977777777', TRUE, TRUE, '1992-05-15', 'MALE', 'LOCAL', TRUE),
(3, 'staff@aureliabook.vn', '$2a$12$fvtIT61vJL0mXABLf9Ss8Opoy8hZh90nxZ4mzcRVXnkV1zIrWPeRK', 'Trần Thu Ngân (Sale Staff)', '0966666666', TRUE, TRUE, '1995-10-20', 'FEMALE', 'LOCAL', TRUE),
(4, 'customer@aureliabook.vn', '$2a$12$ANd2LdYem20MpRFNMoDd1eJD.1dWA6xNF/Eg3M54pxcraU4VhIIMW', 'Lê Khách Hàng (Customer)', '0944444444', TRUE, TRUE, '1998-12-12', 'OTHER', 'LOCAL', TRUE),

-- 2. Supplementary / Testing Accounts
(2, 'kho.nguyen@aureliabook.vn', '$2a$12$svqPVfiYIVljhmiwl6oZ0eiYbZ4cU1hw7tBwywLbgkX4oa4I2ynH2', 'Nguyễn Văn Kho (Manager)', '0971111111', TRUE, TRUE, '1991-03-10', 'MALE', 'LOCAL', TRUE),
(3, 'lan.tran@aureliabook.vn', '$2a$12$fvtIT61vJL0mXABLf9Ss8Opoy8hZh90nxZ4mzcRVXnkV1zIrWPeRK', 'Trần Thị Lan (Sale Staff)', '0961111111', TRUE, TRUE, '1996-08-25', 'FEMALE', 'LOCAL', TRUE),
(4, 'anhntdce200031@gmail.com', '$2a$12$ANd2LdYem20MpRFNMoDd1eJD.1dWA6xNF/Eg3M54pxcraU4VhIIMW', 'Lê Khách Hàng (Customer)', '0955555555', TRUE, TRUE, '1999-09-09', 'OTHER', 'LOCAL', TRUE)
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    password_hash = VALUES(password_hash),
    full_name = VALUES(full_name),
    phone = VALUES(phone),
    email_verified = VALUES(email_verified),
    phone_verified = VALUES(phone_verified),
    dob = VALUES(dob),
    gender = VALUES(gender),
    is_active = VALUES(is_active);
