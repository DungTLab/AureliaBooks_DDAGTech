-- =============================================================================
-- SAMPLE SEED DATA FOR STOCK LEDGER & CATALOG TESTING (FND-03 / SCR-DB-08)
-- =============================================================================
USE aurelia_books_db;

-- 1. Seed demo back-office and warehouse users
INSERT INTO users (id, role_id, email, password_hash, full_name, phone, is_active, auth_provider) VALUES
(1, 1, 'admin@aureliabook.vn', '$2a$12$DUMMY_BCRYPT_HASH_FOR_DEV_ADMIN_12345678901234567890', 'Quản Trị Viên (Dev)', '0988888888', TRUE, 'LOCAL'),
(2, 2, 'kho.nguyen@aureliabook.vn', '$2a$12$DUMMY_BCRYPT_HASH_FOR_DEV_ADMIN_12345678901234567890', 'Nguyễn Văn Kho', '0977777777', TRUE, 'LOCAL'),
(3, 3, 'lan.tran@aureliabook.vn', '$2a$12$DUMMY_BCRYPT_HASH_FOR_DEV_ADMIN_12345678901234567890', 'Trần Thị Lan', '0966666666', TRUE, 'LOCAL')
ON DUPLICATE KEY UPDATE full_name=VALUES(full_name);

-- 2. Seed catalog products
INSERT INTO products (id, barcode, title, category_id, product_type, price, original_cost, weight_grams, stock_quantity, is_active) VALUES
(1, '8935212345678', 'Clean Code - Nghệ Thuật Viết Mã Sạch', 4, 'BOOK', 280000.00, 180000.00, 450, 45, TRUE),
(2, '8935212345679', 'Đắc Nhân Tâm', 2, 'BOOK', 86000.00, 50000.00, 320, 120, TRUE),
(3, '8935212345680', 'Nhà Giả Kim', 2, 'BOOK', 79000.00, 45000.00, 250, 80, TRUE),
(4, '8935212345681', 'Hoàng Tử Bé (Bản Dịch Minh Họa)', 2, 'BOOK', 115000.00, 70000.00, 280, 65, TRUE),
(5, '8935212345682', 'Bút Ký Cao Cấp Thiên Long TL-079', 6, 'STATIONERY', 45000.00, 25000.00, 50, 200, TRUE),
(6, '8935212345683', 'Sổ Da Bìa Còng Cao Cấp Deli A5', 7, 'STATIONERY', 85000.00, 48000.00, 350, 50, TRUE)
ON DUPLICATE KEY UPDATE stock_quantity=VALUES(stock_quantity);

-- 3. Seed immutable stock movement ledger logs (FND-03 / SCR-DB-08)
-- Strict invariant: current_stock = previous_stock + quantity_change
INSERT INTO stock_movement_logs (id, product_id, transaction_type, quantity_change, previous_stock, current_stock, reference_code, performed_by_user_id, note, created_at) VALUES
(1, 1, 'IMPORT', 50, 0, 50, 'GRN-202610-001', 2, 'Nhập kho đợt 1 từ Nhà Xuất Bản Trẻ', DATE_SUB(NOW(), INTERVAL 5 DAY)),
(2, 1, 'ORDER_DEDUCT', -5, 50, 45, 'ORD-202610-101', 3, 'Xuất kho giao đơn hàng trực tuyến #101', DATE_SUB(NOW(), INTERVAL 4 DAY)),
(3, 2, 'IMPORT', 150, 0, 150, 'GRN-202610-002', 2, 'Nhập bổ sung sách bestseller tựu trường', DATE_SUB(NOW(), INTERVAL 4 DAY)),
(4, 2, 'ORDER_DEDUCT', -30, 150, 120, 'ORD-202610-102', 3, 'Xuất kho bán buôn cho thư viện trường học', DATE_SUB(NOW(), INTERVAL 3 DAY)),
(5, 3, 'IMPORT', 100, 0, 100, 'GRN-202610-003', 2, 'Nhập kho ấn bản tái bản kỷ niệm', DATE_SUB(NOW(), INTERVAL 3 DAY)),
(6, 3, 'ORDER_DEDUCT', -20, 100, 80, 'ORD-202610-103', 3, 'Xuất kho giao khách hàng thanh toán VNPay', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(7, 3, 'ORDER_DEDUCT', -3, 80, 77, 'ORD-202610-104', 3, 'Đơn hàng bán lẻ tại quầy', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(8, 3, 'ORDER_CANCELLED_RESTOCK', 3, 77, 80, 'ORD-CANCEL-104', 2, 'Khách báo hủy đơn trước khi bưu tá lấy hàng', DATE_SUB(NOW(), INTERVAL 1 DAY)),
(9, 4, 'IMPORT', 70, 0, 70, 'GRN-202610-004', 2, 'Nhập sách thiếu nhi minh họa màu', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(10, 4, 'MANUAL_ADJUSTMENT', -5, 70, 65, 'ADJ-202610-001', 2, 'Điều chỉnh kiểm kê: sách bị móp rách gáy khi bốc xếp', DATE_SUB(NOW(), INTERVAL 1 DAY)),
(11, 5, 'IMPORT', 200, 0, 200, 'GRN-202610-005', 2, 'Nhập văn phòng phẩm bút ký Thiên Long', DATE_SUB(NOW(), INTERVAL 1 DAY)),
(12, 6, 'IMPORT', 50, 0, 50, 'GRN-202610-006', 2, 'Nhập sổ da Deli chính hãng', NOW())
ON DUPLICATE KEY UPDATE note=VALUES(note);
