-- =============================================================================
-- SEED DATA CƠ SỞ CHO HỆ THỐNG AURELIABOOK (SWP391)
-- =============================================================================
USE AureliaBooks;

-- 1. Khởi tạo 4 vai trò chuẩn RBAC
INSERT INTO roles (id, role_name, description) VALUES
(1, 'ROLE_ADMIN', 'Quản trị viên toàn quyền hệ thống'),
(2, 'ROLE_MANAGER', 'Quản lý cửa hàng, duyệt phiếu nhập và quản lý danh mục'),
(3, 'ROLE_SALE_STAFF', 'Nhân viên bán hàng xử lý đơn hàng và lập phiếu nhập kho DRAFT'),
(4, 'ROLE_CUSTOMER', 'Khách hàng mua sách và quản lý đơn hàng cá nhân')
ON DUPLICATE KEY UPDATE description=VALUES(description);

-- 2. Khởi tạo danh mục gốc (Categories khớp 100% schema_v2_mvp.sql)
INSERT INTO categories (id, name, parent_id, description, is_active) VALUES
(1, 'Sách Tiếng Việt', NULL, 'Toàn bộ các tác phẩm sách xuất bản tiếng Việt', TRUE),
(2, 'Văn Học - Tiểu Thuyết', 1, 'Tiểu thuyết, truyện ngắn, tác phẩm văn học trong và ngoài nước', TRUE),
(3, 'Kinh Tế - Kinh Doanh', 1, 'Sách kỹ năng kinh doanh, quản trị, đầu tư', TRUE),
(4, 'Khoa Học - Kỹ Thuật', 1, 'Sách lập trình, công nghệ thông tin, khoa học tự nhiên', TRUE),
(5, 'Văn Phòng Phẩm', NULL, 'Dụng cụ học tập, đồ dùng văn phòng cao cấp', TRUE),
(6, 'Bút - Viết', 5, 'Bút ký, bút bi, bút máy, bút chì', TRUE),
(7, 'Sổ - Tập Vở', 5, 'Sổ tay bìa da, tập vở ghi chép, sổ lò xo', TRUE)
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 3. Khởi tạo Nhà Xuất Bản mẫu
INSERT INTO publishers (id, name, address, phone, email) VALUES
(1, 'Nhà Xuất Bản Trẻ', '161B Lý Chính Thắng, P. Võ Thị Sáu, Q.3, TP.HCM', '02839316289', 'hopthu@nxbtre.com.vn'),
(2, 'Nhà Xuất Bản Kim Đồng', '55 Quang Trung, Hà Nội', '02439434730', 'cskh_online@nxbkimdong.com.vn'),
(3, 'Nhà Xuất Bản Phụ Nữ', '39 Hàng Chuối, Hà Nội', '02439710741', 'truyenthongnxbpn@gmail.com')
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 4. Khởi tạo Tác Giả mẫu
INSERT INTO authors (id, name, biography) VALUES
(1, 'Nguyễn Nhật Ánh', 'Nhà văn hiện đại nổi tiếng nhất Việt Nam dành cho lứa tuổi thanh thiếu niên'),
(2, 'Robert C. Martin', 'Uncle Bob - Tác giả cuốn sách kinh điển Clean Code và Clean Architecture'),
(3, 'Antoine de Saint-Exupéry', 'Nhà văn, phi công Pháp - Tác giả Hoàng Tử Bé')
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 5. Khởi tạo Thương Hiệu VPP mẫu
INSERT INTO brands (id, name, origin_country) VALUES
(1, 'Thiên Long', 'Việt Nam'),
(2, 'Deli', 'Trung Quốc'),
(3, 'Pentel', 'Nhật Bản')
ON DUPLICATE KEY UPDATE name=VALUES(name);
