-- =============================================================================
-- FOUNDATIONAL SEED DATA FOR AURELIABOOK SYSTEM (SWP391)
-- =============================================================================
SET NAMES 'utf8mb4';
SET CHARACTER SET utf8mb4;

USE aurelia_books_db;

-- 1. Initialize 4 standard RBAC system roles
INSERT INTO roles (id, role_name, description) VALUES
(1, 'ROLE_ADMIN', 'Full system administrative access and security control'),
(2, 'ROLE_MANAGER', 'Store manager: catalog management and goods receipt approval'),
(3, 'ROLE_SALE_STAFF', 'Sales staff: order fulfillment and DRAFT goods receipt creation'),
(4, 'ROLE_CUSTOMER', 'Standard customer account: product browsing and personal orders')
ON DUPLICATE KEY UPDATE description=VALUES(description);

-- 2. Initialize primary root and sub-categories (100% aligned with schema)
INSERT INTO categories (id, name, parent_id, description, is_active) VALUES
(1, N'Sách Tiếng Việt', NULL, 'Comprehensive collection of Vietnamese language published books', TRUE),
(2, N'Văn Học - Tiểu Thuyết', 1, 'Novels, short stories, and literary masterpieces domestic and foreign', TRUE),
(3, N'Kinh Tế - Kinh Doanh', 1, 'Business management, finance, investment, and leadership books', TRUE),
(4, N'Khoa Học - Kỹ Thuật', 1, 'Computer science, programming, AI, and natural sciences', TRUE),
(5, N'Văn Phòng Phẩm', NULL, 'Premium stationery, school and office supplies', TRUE),
(6, N'Bút - Viết', 5, 'Luxury signature pens, rollerball pens, fountain pens, and pencils', TRUE),
(7, N'Sổ - Tập Vở', 5, 'Leather journals, planners, spiral notebooks, and memo pads', TRUE)
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 3. Initialize sample book publishers
INSERT INTO publishers (id, name, address, email) VALUES
(1, N'Nhà Xuất Bản Trẻ', N'161B Lý Chính Thắng, P. Võ Thị Sáu, Q.3, TP.HCM', 'hopthu@nxbtre.com.vn'),
(2, N'Nhà Xuất Bản Kim Đồng', N'55 Quang Trung, Hà Nội', 'cskh_online@nxbkimdong.com.vn'),
(3, N'Nhà Xuất Bản Phụ Nữ', N'39 Hàng Chuối, Hà Nội', 'truyenthongnxbpn@gmail.com')
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 4. Initialize sample authors
INSERT INTO authors (id, name, biography) VALUES
(1, N'Nguyễn Nhật Ánh', 'Foremost contemporary Vietnamese author celebrated for youth and coming-of-age literature'),
(2, 'Robert C. Martin', 'Uncle Bob - Legendary software craftsman, author of Clean Code and Clean Architecture'),
(3, N'Antoine de Saint-Exupéry', 'French aviator and author of The Little Prince (Le Petit Prince)')
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 5. Initialize sample stationery brands
INSERT INTO brands (id, name, origin_country) VALUES
(1, N'Thiên Long', N'Việt Nam'),
(2, 'Deli', N'Trung Quốc'),
(3, 'Pentel', N'Nhật Bản')
ON DUPLICATE KEY UPDATE name=VALUES(name);
