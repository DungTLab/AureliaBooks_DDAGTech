-- =============================================================================
-- SYSTEM SEED USERS FOR AURELIABOOK (SWP391)
-- =============================================================================
SET NAMES 'utf8mb4';
SET CHARACTER SET utf8mb4;

USE aurelia_books_db;

-- BCrypt password hashes (cost factor = 12):
-- Admin@123    -> $2a$12$MyjwLKg2SE5PUCbaEZlei.wuhiouXbtTgstqcWAitLKG9vnl6nXUm
-- Manager@123  -> $2a$12$svqPVfiYIVljhmiwl6oZ0eiYbZ4cU1hw7tBwywLbgkX4oa4I2ynH2
-- Staff@123    -> $2a$12$fvtIT61vJL0mXABLf9Ss8Opoy8hZh90nxZ4mzcRVXnkV1zIrWPeRK
-- Customer@123 -> $2a$12$ANd2LdYem20MpRFNMoDd1eJD.1dWA6xNF/Eg3M54pxcraU4VhIIMW

-- 1. Ensure existing accounts have known passwords
UPDATE users SET password_hash = '$2a$12$MyjwLKg2SE5PUCbaEZlei.wuhiouXbtTgstqcWAitLKG9vnl6nXUm', is_active = TRUE WHERE email = 'admin@aureliabook.vn';
UPDATE users SET password_hash = '$2a$12$svqPVfiYIVljhmiwl6oZ0eiYbZ4cU1hw7tBwywLbgkX4oa4I2ynH2', is_active = TRUE WHERE email = 'kho.nguyen@aureliabook.vn';
UPDATE users SET password_hash = '$2a$12$fvtIT61vJL0mXABLf9Ss8Opoy8hZh90nxZ4mzcRVXnkV1zIrWPeRK', is_active = TRUE WHERE email = 'lan.tran@aureliabook.vn';
UPDATE users SET password_hash = '$2a$12$ANd2LdYem20MpRFNMoDd1eJD.1dWA6xNF/Eg3M54pxcraU4VhIIMW', is_active = TRUE WHERE email = 'anhntdce200031@gmail.com';

-- 2. Insert standard role accounts if not present
INSERT INTO users (role_id, email, password_hash, full_name, phone, dob, gender, auth_provider, is_active)
VALUES
(1, 'admin@aureliabook.vn', '$2a$12$MyjwLKg2SE5PUCbaEZlei.wuhiouXbtTgstqcWAitLKG9vnl6nXUm', 'Quản Trị Viên (Admin)', '0988888888', '1990-01-01', 'MALE', 'LOCAL', TRUE),
(2, 'manager@aureliabook.vn', '$2a$12$svqPVfiYIVljhmiwl6oZ0eiYbZ4cU1hw7tBwywLbgkX4oa4I2ynH2', 'Nguyễn Quản Lý (Manager)', '0977777777', '1992-05-15', 'MALE', 'LOCAL', TRUE),
(3, 'staff@aureliabook.vn', '$2a$12$fvtIT61vJL0mXABLf9Ss8Opoy8hZh90nxZ4mzcRVXnkV1zIrWPeRK', 'Trần Thu Ngân (Sale Staff)', '0966666666', '1995-10-20', 'FEMALE', 'LOCAL', TRUE),
(4, 'customer@aureliabook.vn', '$2a$12$ANd2LdYem20MpRFNMoDd1eJD.1dWA6xNF/Eg3M54pxcraU4VhIIMW', 'Lê Khách Hàng (Customer)', '0944444444', '1998-12-12', 'OTHER', 'LOCAL', TRUE)
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    password_hash = VALUES(password_hash),
    full_name = VALUES(full_name),
    is_active = VALUES(is_active);
