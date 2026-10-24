-- Foundational RBAC roles only; local catalog/users live under docker/demo.
SET NAMES utf8mb4;
USE aurelia_books_db;

INSERT INTO roles (role_name, description) VALUES
('ROLE_ADMIN', 'System administrator'),
('ROLE_MANAGER', 'Store manager'),
('ROLE_SALE_STAFF', 'Sales staff'),
('ROLE_CUSTOMER', 'Customer')
ON DUPLICATE KEY UPDATE role_name=roles.role_name;
