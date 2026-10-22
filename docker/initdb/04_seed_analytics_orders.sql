-- =============================================================================
-- SEED DATA FOR UC26 (Revenue Analytics Dashboard) & UC27 (Excel Export Engine)
-- =============================================================================
SET NAMES 'utf8mb4';
SET CHARACTER SET utf8mb4;

USE aurelia_books_db;

-- 1. Categories
INSERT INTO categories (id, name, description, is_active)
VALUES 
    (101, 'Sách Văn Học & Kỹ Năng', 'Các đầu sách bán chạy nhất', TRUE),
    (102, 'Sách Kinh Tế & Kinh Doanh', 'Sách tài chính, quản trị', TRUE),
    (103, 'Văn Phòng Phẩm & Bút Viết', 'Dụng cụ văn phòng phẩm cao cấp', TRUE),
    (104, 'Sổ Tay & Giấy Vở', 'Vở ghi chép, sổ tay', TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), is_active = TRUE;

-- 2. Products (Books & Stationery)
INSERT INTO products (id, barcode, title, category_id, product_type, price, original_cost, weight_grams, stock_quantity, is_active, main_image_url)
VALUES 
    (1001, '8935244889911', 'Nhà Giả Kim - Paulo Coelho', 101, 'BOOK', 79000.00, 48000.00, 250, 150, TRUE, 'https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c'),
    (1002, '8935244889928', 'Đắc Nhân Tâm - Dale Carnegie', 101, 'BOOK', 96000.00, 58000.00, 320, 85, TRUE, 'https://images.unsplash.com/photo-1544947950-fa07a98d237f'),
    (1003, '8935244889935', 'Tuổi Trẻ Đáng Giá Bao Nhiêu - Rosie Nguyễn', 101, 'BOOK', 88000.00, 52000.00, 280, 45, TRUE, 'https://images.unsplash.com/photo-1512820790803-83ca734da794'),
    (1004, '8935244889942', 'Tư Duy Nhanh Và Chậm - Daniel Kahneman', 102, 'BOOK', 185000.00, 115000.00, 500, 30, TRUE, 'https://images.unsplash.com/photo-1589829085413-56de8ae18c73'),
    (1005, '8935244889959', 'Cây Cam Ngọt Của Tôi - José Mauro', 101, 'BOOK', 108000.00, 68000.00, 300, 60, TRUE, 'https://images.unsplash.com/photo-1495446815901-a7297e633e8d'),
    (1006, '8935244889966', 'Cha Giàu Cha Nghèo - Robert Kiyosaki', 102, 'BOOK', 125000.00, 78000.00, 380, 8, TRUE, 'https://images.unsplash.com/photo-1532012164546-f432f2e3777f'),
    (2001, '8935001122001', 'Hộp 20 Bút Bi Thiên Long Gel 0.5mm Xanh', 103, 'STATIONERY', 95000.00, 62000.00, 180, 220, TRUE, 'https://images.unsplash.com/photo-1583485088034-697b5bc54ccd'),
    (2002, '8935001122002', 'Lốc 5 Cuốn Vở Kẻ Ngang Campus B5 200 Trang', 104, 'STATIONERY', 140000.00, 92000.00, 600, 180, TRUE, 'https://images.unsplash.com/photo-1517842645767-c639042777db'),
    (2003, '8935001122003', 'Bộ Bút Dạ Quang Pastel Deli 6 Màu', 103, 'STATIONERY', 55000.00, 32000.00, 120, 15, TRUE, 'https://images.unsplash.com/photo-1585336261026-61699920b7ee'),
    (2004, '8935001122004', 'Set 5 Cuộn Băng Keo Washi Tape Vintage', 103, 'STATIONERY', 45000.00, 24000.00, 80, 75, TRUE, 'https://images.unsplash.com/photo-1513542789411-b6a5d4f31634'),
    (2005, '8935001122005', 'Máy Gọt Bút Chì Điện Tử Tự Động Deli', 103, 'STATIONERY', 135000.00, 85000.00, 250, 0, TRUE, 'https://images.unsplash.com/photo-1586075010923-2dd4570fb338')
ON DUPLICATE KEY UPDATE 
    title = VALUES(title),
    price = VALUES(price),
    original_cost = VALUES(original_cost),
    stock_quantity = VALUES(stock_quantity),
    is_active = TRUE;
