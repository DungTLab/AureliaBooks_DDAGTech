-- =============================================================================
-- SAMPLE SEED DATA: 10 PRODUCTS (6 BOOKS + 4 STATIONERIES) (FE-2 CATALOG)
-- Compatible with AureliaBook MySQL 8.0 Schema (schema.sql)
-- Charset: utf8mb4 | Collation: utf8mb4_0900_ai_ci
-- =============================================================================

SET NAMES 'utf8mb4';
SET CHARACTER SET utf8mb4;

USE aurelia_books_db;

START TRANSACTION;

-- -----------------------------------------------------------------------------
-- 1. SEED CATEGORIES (Hierarchical Parent & Subcategories)
-- -----------------------------------------------------------------------------
INSERT INTO categories (id, name, parent_id, description, is_active)
VALUES
(1, 'Sách Trong Nước', NULL, 'Toàn bộ các đầu sách xuất bản và phát hành tại Việt Nam', TRUE),
(2, 'Văn Học', 1, 'Tiểu thuyết, truyện ngắn, tản văn và thơ ca', TRUE),
(3, 'Kinh Tế', 1, 'Quản trị kinh doanh, tài chính, đầu tư và khởi nghiệp', TRUE),
(4, 'Tâm Lý - Kỹ Năng Sống', 1, 'Phát triển bản thân, tâm lý học ứng dụng và kỹ năng giao tiếp', TRUE),
(5, 'Sách Thiếu Nhi', 1, 'Truyện tranh, cổ tích và sách kiến thức bách khoa cho thiếu nhi', TRUE),
(6, 'Văn Phòng Phẩm', NULL, 'Dụng cụ học tập, đồ dùng văn phòng và họa phẩm mỹ thuật', TRUE),
(7, 'Dụng Cụ Học Tập', 6, 'Bút viết, thước kẻ, compa, gôm tẩy và hộp bút', TRUE),
(8, 'Dụng Cụ Vẽ', 6, 'Màu nước, chì màu, cọ vẽ, giấy vẽ chuyên dụng', TRUE)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    parent_id = VALUES(parent_id),
    description = VALUES(description),
    is_active = VALUES(is_active);

-- -----------------------------------------------------------------------------
-- 2. SEED PUBLISHERS (UC18)
-- -----------------------------------------------------------------------------
INSERT INTO publishers (id, name, address, email)
VALUES
(1, 'NXB Trẻ', '161B Lý Chính Thắng, P. Võ Thị Sáu, Q.3, TP.HCM', 'hopthu@nxbtre.com.vn'),
(2, 'NXB Kim Đồng', '55 Quang Trung, Hai Bà Trưng, Hà Nội', 'info@nxbkimdong.com.vn'),
(3, 'NXB Hội Nhà Văn', '65 Nguyễn Du, Hai Bà Trưng, Hà Nội', 'nxbhoinhavan@gmail.com'),
(4, 'NXB Tổng Hợp TP.HCM', '62 Nguyễn Thị Minh Khai, P. Đa Kao, Q.1, TP.HCM', 'tonghop@nxbhcm.com.vn')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    address = VALUES(address),
    email = VALUES(email);

-- -----------------------------------------------------------------------------
-- 3. SEED AUTHORS (UC17)
-- -----------------------------------------------------------------------------
INSERT INTO authors (id, name, biography)
VALUES
(1, 'Nguyễn Nhật Ánh', 'Nhà văn Việt Nam nổi tiếng với những tác phẩm viết về tuổi thơ, tình bạn và tuổi học trò trong trẻo.'),
(2, 'Paulo Coelho', 'Tiểu thuyết gia người Brasil, tác giả của Nhà Giả Kim - một trong những cuốn sách bán chạy nhất mọi thời đại.'),
(3, 'Haruki Murakami', 'Nhà văn đương đại Nhật Bản với phong cách văn chương hiện thực huyền ảo sâu lắng.'),
(4, 'Dale Carnegie', 'Tác giả, diễn giả truyền cảm hứng người Mỹ, người sáng lập chuỗi đào tạo nghệ thuật giao tiếp nổi tiếng.'),
(5, 'Tô Hoài', 'Nhà văn lớn của nền văn học Việt Nam hiện đại, cây đại thụ của văn học thiếu nhi với kiệt tác Dế Mèn.')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    biography = VALUES(biography);

-- -----------------------------------------------------------------------------
-- 4. SEED BRANDS (UC19)
-- -----------------------------------------------------------------------------
INSERT INTO brands (id, name, origin_country)
VALUES
(1, 'Thiên Long', 'Việt Nam'),
(2, 'Deli', 'Trung Quốc'),
(3, 'Pentel', 'Nhật Bản'),
(4, 'Faber-Castell', 'Đức')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    origin_country = VALUES(origin_country);

-- -----------------------------------------------------------------------------
-- 5. SEED PRODUCTS (6 Books + 4 Stationeries)
-- -----------------------------------------------------------------------------
INSERT INTO products (id, barcode, title, category_id, product_type, price, original_cost, weight_grams, stock_quantity, is_active, main_image_url, tags, description)
VALUES
-- Sản phẩm 1 (Sách)
(1, '8934974181231', 'Cho Tôi Xin Một Vé Đi Tuổi Thơ', 2, 'BOOK', 85000.00, 55000.00, 250, 50, TRUE,
 'https://images.unsplash.com/photo-1544947950-fa07a98d237f?q=80&w=600&auto=format&fit=crop',
 'tuoi-tho,van-hoc,nguyen-nhat-anh',
 'Một tác phẩm dịu dàng đưa người đọc trở về những tháng năm thơ ấu hồn nhiên, tươi đẹp và ngập tràn ký ức tuổi học trò.'),

-- Sản phẩm 2 (Sách)
(2, '8934974181248', 'Mắt Biếc', 2, 'BOOK', 110000.00, 70000.00, 300, 35, TRUE,
 'https://images.unsplash.com/photo-1512820790803-83ca734da794?q=80&w=600&auto=format&fit=crop',
 'tinh-cam,van-hoc-viet,mat-biec',
 'Câu chuyện tình đơn phương da diết của Ngạn dành cho Hà Lan nơi làng Đo Đo mộc mạc và hoài niệm.'),

-- Sản phẩm 3 (Sách)
(3, '8935244874389', 'Nhà Giả Kim (The Alchemist)', 4, 'BOOK', 79000.00, 50000.00, 220, 80, TRUE,
 'https://images.unsplash.com/photo-1543002588-bfa74002ed7e?q=80&w=600&auto=format&fit=crop',
 'ky-nang,triet-ly,uoc-mo',
 'Hành trình vượt sa mạc tìm kiếm kho báu của chàng chăn cừu Santiago, khẳng định thông điệp: khi bạn khao khát một điều gì, cả vũ trụ sẽ hợp lực giúp bạn.'),

-- Sản phẩm 4 (Sách)
(4, '8935235226159', 'Rừng Na Uy', 2, 'BOOK', 135000.00, 90000.00, 450, 25, TRUE,
 'https://images.unsplash.com/photo-1495640388908-05fa85288e61?q=80&w=600&auto=format&fit=crop',
 'van-hoc-nhat,haruki-murakami',
 'Tiểu thuyết kinh điển về sự trưởng thành, nỗi cô đơn và những mất mát trong tâm hồn tuổi trẻ Tokyo thập niên 60.'),

-- Sản phẩm 5 (Sách)
(5, '8935086853245', 'Đắc Nhân Tâm', 4, 'BOOK', 98000.00, 60000.00, 320, 100, TRUE,
 'https://images.unsplash.com/photo-1589829085413-56de8ae18c73?q=80&w=600&auto=format&fit=crop',
 'giao-tiep,dac-nhan-tam,phat-trien-ban-than',
 'Cuốn sách gối đầu giường về nghệ thuật thu phục lòng người và xây dựng các mối quan hệ bền vững trong cuộc sống và sự nghiệp.'),

-- Sản phẩm 6 (Sách)
(6, '8935244840124', 'Dế Mèn Phiêu Lưu Ký', 5, 'BOOK', 65000.00, 40000.00, 200, 60, TRUE,
 'https://images.unsplash.com/photo-1532012164546-f432f2e3777f?q=80&w=600&auto=format&fit=crop',
 'thieu-nhi,phieu-luu,to-hoai',
 'Chuyến phiêu lưu kỳ thú của chú Dế Mèn gan dạ qua thế giới côn trùng, mang thông điệp về tình bằng hữu và khát vọng hòa bình.'),

-- Sản phẩm 7 (VPP)
(7, '8935001800018', 'Hộp 20 Bút Bi Thiên Long TL-027 (Mực Xanh)', 7, 'STATIONERY', 95000.00, 65000.00, 180, 150, TRUE,
 'https://images.unsplash.com/photo-1583485088034-697b5bc54ccd?q=80&w=600&auto=format&fit=crop',
 'but-bi,thien-long,hoc-tap',
 'Dòng bút bi quốc dân với nét viết êm trơn, mực đậm đều, thiết kế kẹp bấm tiện lợi phù hợp cho học sinh và văn phòng.'),

-- Sản phẩm 8 (VPP)
(8, '6921734958123', 'Hộp 12 Bút Chì Gỗ Deli 2B Kèm Tẩy', 7, 'STATIONERY', 42000.00, 25000.00, 120, 80, TRUE,
 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?q=80&w=600&auto=format&fit=crop',
 'but-chi,deli,hoc-tap',
 'Bút chì than 2B ngòi mềm chuốt không gãy, độ đậm chuẩn dành cho viết bài và phác thảo hình ảnh.'),

-- Sản phẩm 9 (VPP)
(9, '4902506285126', 'Hộp Màu Nước Pentel 12 Màu (6ml/tuýp)', 8, 'STATIONERY', 145000.00, 95000.00, 250, 40, TRUE,
 'https://images.unsplash.com/photo-1513364776144-60967b0f800f?q=80&w=600&auto=format&fit=crop',
 'mau-nuoc,pentel,hoa-pham',
 'Màu nước mỹ thuật cao cấp từ Pentel Nhật Bản, hạt màu mịn, dễ pha trộn, độ phủ tốt và bền màu theo thời gian.'),

-- Sản phẩm 10 (VPP)
(10, '4005401158245', 'Hộp Bút Chì Màu Faber-Castell Classic (24 Màu)', 8, 'STATIONERY', 185000.00, 120000.00, 300, 30, TRUE,
 'https://images.unsplash.com/photo-1502082553048-f009c37129b9?q=80&w=600&auto=format&fit=crop',
 'chi-mau,faber-castell,hoa-cu',
 'Bút chì màu thân lục giác dễ cầm, màu sắc tươi sáng rực rỡ, sử dụng công nghệ dán SV giúp lõi chì chống gãy vượt trội.')
ON DUPLICATE KEY UPDATE
    barcode = VALUES(barcode),
    title = VALUES(title),
    category_id = VALUES(category_id),
    product_type = VALUES(product_type),
    price = VALUES(price),
    original_cost = VALUES(original_cost),
    weight_grams = VALUES(weight_grams),
    stock_quantity = VALUES(stock_quantity),
    is_active = VALUES(is_active),
    main_image_url = VALUES(main_image_url),
    tags = VALUES(tags),
    description = VALUES(description);

-- -----------------------------------------------------------------------------
-- 6. SEED BOOKS SPECIFIC DETAILS (IS-A Relationship: books table)
-- -----------------------------------------------------------------------------
INSERT INTO books (product_id, isbn, publisher_id, series_name, volume_number, is_textbook, publication_year, edition, page_count, language, cover_type)
VALUES
(1, '9786041181231', 1, NULL, NULL, FALSE, 2023, 'Tái bản lần thứ 65', 216, 'Tiếng Việt', 'PAPERBACK'),
(2, '9786041181248', 1, NULL, NULL, FALSE, 2022, 'Tái bản có tranh minh họa', 300, 'Tiếng Việt', 'PAPERBACK'),
(3, '9786043651591', 3, NULL, NULL, FALSE, 2024, 'Tái bản mới nhất', 228, 'Tiếng Việt', 'PAPERBACK'),
(4, '9786045371565', 3, NULL, NULL, FALSE, 2023, 'Bản in đặc biệt kỷ niệm', 550, 'Tiếng Việt', 'PAPERBACK'),
(5, '9786045882351', 4, NULL, NULL, FALSE, 2024, 'Bản dịch độc quyền First News', 320, 'Tiếng Việt', 'PAPERBACK'),
(6, '9786042180456', 2, NULL, NULL, FALSE, 2023, 'Tái bản kỷ niệm', 192, 'Tiếng Việt', 'PAPERBACK')
ON DUPLICATE KEY UPDATE
    isbn = VALUES(isbn),
    publisher_id = VALUES(publisher_id),
    series_name = VALUES(series_name),
    volume_number = VALUES(volume_number),
    is_textbook = VALUES(is_textbook),
    publication_year = VALUES(publication_year),
    edition = VALUES(edition),
    page_count = VALUES(page_count),
    language = VALUES(language),
    cover_type = VALUES(cover_type);

-- -----------------------------------------------------------------------------
-- 7. SEED BOOK_AUTHORS (Many-to-Many junction)
-- -----------------------------------------------------------------------------
INSERT INTO book_authors (book_id, author_id)
VALUES
(1, 1), -- Cho Tôi Xin Một Vé Đi Tuổi Thơ -> Nguyễn Nhật Ánh
(2, 1), -- Mắt Biếc -> Nguyễn Nhật Ánh
(3, 2), -- Nhà Giả Kim -> Paulo Coelho
(4, 3), -- Rừng Na Uy -> Haruki Murakami
(5, 4), -- Đắc Nhân Tâm -> Dale Carnegie
(6, 5)  -- Dế Mèn Phiêu Lưu Ký -> Tô Hoài
ON DUPLICATE KEY UPDATE
    author_id = VALUES(author_id);

-- -----------------------------------------------------------------------------
-- 8. SEED STATIONERIES SPECIFIC DETAILS (IS-A Relationship: stationeries table)
-- -----------------------------------------------------------------------------
INSERT INTO stationeries (product_id, brand_id, material, color, warranty_months)
VALUES
(7, 1, 'Nhựa ABS', 'Xanh Dương', 0),       -- Bút bi Thiên Long
(8, 2, 'Gỗ Linden', 'Vàng', 0),            -- Chì Deli 2B
(9, 3, 'Hạt Màu Khoáng Nước', '12 Màu', 12), -- Màu nước Pentel
(10, 4, 'Gỗ Rừng Trồng Sinh Thái', '24 Màu', 0) -- Chì màu Faber-Castell
ON DUPLICATE KEY UPDATE
    brand_id = VALUES(brand_id),
    material = VALUES(material),
    color = VALUES(color),
    warranty_months = VALUES(warranty_months);

COMMIT;
