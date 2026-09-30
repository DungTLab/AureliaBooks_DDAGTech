-- =============================================================================
-- HỆ THỐNG CSDL THƯƠNG MẠI ĐIỆN TỬ NHÀ SÁCH (AURELIABOOK) - PHIÊN BẢN V2 (MVP TINH GỌN)
-- Hệ quản trị: MySQL 8.0.16+ InnoDB | Charset: utf8mb4 | Collation: utf8mb4_0900_ai_ci
-- Quy mô: 23 Bảng Chuẩn Hóa (Khớp 100% Tài liệu Nghiệp vụ 0 & Phản biện Giảng viên)
-- 
-- CÁC CẢI TIẾN TINH GỌN CHÍNH:
-- 1. Bỏ bảng user_vouchers: Chuyển sang mô hình voucher công khai nhập tay, chống lạm
--    dụng bằng truy vấn SELECT realtime trên bảng orders và order_vouchers.
-- 2. Bỏ bảng inventory_reservations: Chuyển sang quy trình Test kho -> Thanh toán thật
--    -> Success mới trừ kho bằng Spring Data JPA Atomic Update (chống âm kho).
-- 3. Bỏ bảng ai_recommendations: Thay bằng Spring AI SimpleVectorStore In-Memory,
--    lưu vector ngữ nghĩa trong cột embedding (JSON) của bảng products.
-- 4. Bỏ bảng order_status_history: SELECT realtime từ các mốc thời gian trong bảng orders
--    (created_at, confirmed_at, shipped_at, delivered_at, cancelled_at) và audit_logs.
-- 5. Bỏ bảng payment_logs: Gộp mã đối soát VNPay vào bảng orders (vnpay_txn_ref),
--    toàn bộ raw JSON callback lưu vào audit_logs.
-- 6. Bỏ bảng book_series: Gộp thành cột series_name trong bảng books vì MVP không bán combo.
-- 
-- MA TRẬN ĐÁNH XẠ VÀ PHỦ 100% USE CASES (USE CASE TRACEABILITY MATRIX):
-- - PHÂN HỆ 1 (Tài khoản & Địa chỉ): roles, users, user_roles, shipping_addresses -> UC05, UC06, UC07, UC08, UC09, UC28
-- - PHÂN HỆ 2 (Khuyến mãi): vouchers, order_vouchers -> UC11.2, UC13
-- - PHÂN HỆ 3 (Catalog & AI): categories, products, publishers, books, authors, book_authors, brands, stationeries -> UC01, UC02, UC03, UC04, UC15, UC16, UC17, UC18, UC19, UC20, UC21
-- - PHÂN HỆ 4 (Nhập kho & Tồn kho): suppliers, goods_receipts, goods_receipt_items, stock_logs -> UC21, UC22, UC23, UC24, UC25, UC27, UC30
-- - PHÂN HỆ 5 (Giỏ, Đơn & Thanh toán): carts, cart_items, orders, order_items, order_vouchers -> UC10, UC11, UC11.1, UC12, UC12.1, UC14, UC14.1, UC26
-- - PHÂN HỆ 6 (Kiểm toán An ninh): audit_logs -> UC29
-- =============================================================================

DROP DATABASE IF EXISTS AureliaBooks;
CREATE DATABASE AureliaBooks CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE AureliaBooks;

-- -----------------------------------------------------------------------------
-- PHÂN HỆ 1: TÀI KHOẢN, PHÂN QUYỀN & ĐỊA CHỈ (FE-1)
-- -----------------------------------------------------------------------------

CREATE TABLE roles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL,
    description VARCHAR(255) NULL,
    CONSTRAINT uk_roles_name UNIQUE (role_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Danh mục vai trò hệ thống RBAC (Khớp Use Cases: UC06, UC28)';

CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NULL COMMENT 'Mật khẩu BCrypt (Cho phép NULL đối với tài khoản đăng nhập qua Google SSO)',
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(15) NULL COMMENT 'Cho phép NULL khi đăng ký qua Google SSO, bắt buộc cập nhật khi Checkout',
    dob DATE NULL COMMENT 'Cho phép NULL để đơn giản hóa đăng ký',
    gender ENUM('MALE','FEMALE','OTHER') NOT NULL DEFAULT 'OTHER',
    avatar_url VARCHAR(500) NULL COMMENT 'Lấy từ picture URL của Google Profile hoặc upload cục bộ',
    auth_provider ENUM('LOCAL','GOOGLE') NOT NULL DEFAULT 'LOCAL' COMMENT 'Kênh xác thực tài khoản (LOCAL hoặc GOOGLE)',
    provider_id VARCHAR(100) NULL COMMENT 'Định danh duy nhất từ Google Identity Provider (OpenID Connect sub claim)',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_phone UNIQUE (phone),
    CONSTRAINT uk_users_provider UNIQUE (auth_provider, provider_id),
    CONSTRAINT chk_auth_credentials CHECK (
        (auth_provider = 'LOCAL' AND password_hash IS NOT NULL)
        OR
        (auth_provider = 'GOOGLE' AND provider_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tài khoản người dùng và cán bộ nhân viên hỗ trợ Local Auth và Google OAuth2 SSO (Khớp Use Cases: UC05, UC06, UC07, UC08, UC28)';

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bảng nối Nhiều-Nhiều User và Role (Khớp Use Cases: UC06, UC28)';

CREATE TABLE shipping_addresses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    recipient_name VARCHAR(100) NOT NULL,
    phone VARCHAR(15) NOT NULL,
    economic_region ENUM('NORTHERN','CENTRAL_HIGHLANDS','SOUTHERN_MEKONG') NOT NULL,
    province VARCHAR(100) NOT NULL,
    district VARCHAR(100) NOT NULL,
    ward VARCHAR(100) NOT NULL,
    detailed_address VARCHAR(255) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_addr_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_addr_user (user_id, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Sổ địa chỉ nhận hàng của khách hàng (Khớp Use Cases: UC09, UC11)';

-- -----------------------------------------------------------------------------
-- PHÂN HỆ 2: KHUYẾN MÃI (VOUCHER CÔNG KHAI) (FE-3)
-- -----------------------------------------------------------------------------

CREATE TABLE vouchers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(30) NOT NULL,
    description VARCHAR(255) NOT NULL,
    voucher_scope ENUM('SHIPPING','PRODUCT') NOT NULL,
    voucher_type ENUM('PERCENTAGE','FIXED_AMOUNT') NOT NULL,
    discount_percentage DECIMAL(5,2) NULL,
    discount_amount DECIMAL(12,2) NULL,
    max_discount_amount DECIMAL(12,2) NULL,
    min_order_value DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    start_date DATETIME NOT NULL,
    end_date DATETIME NOT NULL,
    usage_limit INT NOT NULL DEFAULT 100 COMMENT 'Tổng số lượt dùng còn lại toàn hệ thống (giảm dần về 0 khi có đơn đặt thành công)',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_vouchers_code UNIQUE (code),
    CONSTRAINT fk_vouch_creator FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_vouch_dates CHECK (end_date > start_date),
    CONSTRAINT chk_vouch_limit CHECK (usage_limit >= 0),
    CONSTRAINT chk_vouch_min_val CHECK (min_order_value >= 0),
    CONSTRAINT chk_vouch_pct CHECK (discount_percentage IS NULL OR (discount_percentage > 0 AND discount_percentage <= 100)),
    CONSTRAINT chk_vouch_amt CHECK (discount_amount IS NULL OR discount_amount > 0),
    CONSTRAINT chk_vouch_type_xor CHECK (
        (voucher_type = 'PERCENTAGE' AND discount_percentage IS NOT NULL AND discount_amount IS NULL)
        OR
        (voucher_type = 'FIXED_AMOUNT' AND discount_amount IS NOT NULL AND discount_percentage IS NULL)
    ),
    CONSTRAINT chk_vouch_max_discount CHECK (max_discount_amount IS NULL OR max_discount_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Danh mục mã giảm giá công khai do Quản lý tạo (Khớp Use Cases: UC11.2, UC13)';

-- -----------------------------------------------------------------------------
-- PHÂN HỆ 3: DANH MỤC & SẢN PHẨM (IS-A ARCHITECTURE + VECTOR AI) (FE-2, FE-6)
-- -----------------------------------------------------------------------------

CREATE TABLE categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    parent_id BIGINT NULL,
    description VARCHAR(255) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cat_parent FOREIGN KEY (parent_id) REFERENCES categories(id) ON DELETE SET NULL,
    INDEX idx_cat_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Danh mục sản phẩm tự tham chiếu đệ quy (Khớp Use Cases: UC01, UC20)';

CREATE TABLE products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    category_id BIGINT NOT NULL,
    product_type ENUM('BOOK','STATIONERY') NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    original_cost DECIMAL(12,2) NOT NULL,
    weight_grams INT NOT NULL DEFAULT 200,
    stock_quantity INT NOT NULL DEFAULT 0 COMMENT 'Số lượng tồn kho khả dụng để bán (ATP - Available to Promise)',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    main_image_url VARCHAR(500) NULL,
    tags VARCHAR(255) NULL COMMENT 'Từ khóa tag phân loại (ví dụ: tam-ly, ky-nang, kinh-doanh)',
    embedding JSON NULL COMMENT 'Vector ngữ nghĩa đa chiều phục vụ Spring AI SimpleVectorStore',
    description LONGTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_products_barcode UNIQUE (barcode),
    CONSTRAINT fk_prod_cat FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE RESTRICT,
    CONSTRAINT chk_prod_price CHECK (price >= 0),
    CONSTRAINT chk_prod_cost CHECK (original_cost >= 0),
    CONSTRAINT chk_prod_weight CHECK (weight_grams >= 0),
    CONSTRAINT chk_prod_stock CHECK (stock_quantity >= 0),
    INDEX idx_prod_search (is_active, category_id, price)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bảng cha của Sách và Văn phòng phẩm tích hợp Vector AI (Khớp Use Cases: UC01, UC02, UC03, UC04, UC15, UC16, UC21)';

CREATE TABLE publishers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    address VARCHAR(255) NULL,
    email VARCHAR(100) NULL,
    CONSTRAINT uk_publishers_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Nhà xuất bản phát hành sách (Khớp Use Cases: UC03, UC18)';

CREATE TABLE books (
    product_id BIGINT PRIMARY KEY,
    isbn VARCHAR(20) NOT NULL,
    publisher_id BIGINT NOT NULL,
    series_name VARCHAR(150) NULL COMMENT 'Tên bộ sách / Series hiển thị nhãn trên UI (thay thế bảng book_series)',
    volume_number INT NULL COMMENT 'Số thứ tự Tập trong Bộ sách',
    is_textbook BOOLEAN NOT NULL DEFAULT FALSE,
    publication_year INT NULL,
    edition VARCHAR(50) NULL,
    page_count INT NULL,
    language VARCHAR(50) NOT NULL DEFAULT 'Tiếng Việt',
    cover_type ENUM('PAPERBACK','HARDCOVER') NOT NULL DEFAULT 'PAPERBACK',
    CONSTRAINT uk_books_isbn UNIQUE (isbn),
    CONSTRAINT fk_book_prod FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT fk_book_pub FOREIGN KEY (publisher_id) REFERENCES publishers(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Thực thể Sách (IS-A Product - Quản lý theo Series/Tập) (Khớp Use Cases: UC01, UC02, UC03, UC15)';

CREATE TABLE authors (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    biography TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tác giả sách (Khớp Use Cases: UC03, UC17)';

CREATE TABLE book_authors (
    book_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    PRIMARY KEY (book_id, author_id),
    CONSTRAINT fk_ba_book FOREIGN KEY (book_id) REFERENCES books(product_id) ON DELETE CASCADE,
    CONSTRAINT fk_ba_author FOREIGN KEY (author_id) REFERENCES authors(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bảng liên kết Sách và Tác giả (Khớp Use Cases: UC03, UC15, UC17)';

CREATE TABLE brands (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    origin_country VARCHAR(100) NULL,
    CONSTRAINT uk_brands_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Thương hiệu Văn phòng phẩm (Khớp Use Cases: UC03, UC19)';

CREATE TABLE stationeries (
    product_id BIGINT PRIMARY KEY,
    brand_id BIGINT NOT NULL,
    material VARCHAR(100) NULL,
    color VARCHAR(50) NULL,
    warranty_months INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_stat_prod FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT fk_stat_brand FOREIGN KEY (brand_id) REFERENCES brands(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Thực thể Văn phòng phẩm (IS-A Product) (Khớp Use Cases: UC01, UC02, UC03, UC16)';

-- -----------------------------------------------------------------------------
-- PHÂN HỆ 4: NHẬP KHO & TỒN KHO BẤT BIẾN (FE-5)
-- -----------------------------------------------------------------------------

CREATE TABLE suppliers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    contact_name VARCHAR(100) NULL,
    phone VARCHAR(15) NOT NULL,
    email VARCHAR(100) NULL,
    address VARCHAR(255) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Nhà cung cấp hàng hóa (Khớp Use Cases: UC22, UC30)';

CREATE TABLE goods_receipts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_code VARCHAR(32) NOT NULL,
    supplier_id BIGINT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    status ENUM('DRAFT','RECEIVED') NOT NULL DEFAULT 'DRAFT',
    total_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    note VARCHAR(500) NULL,
    received_at DATETIME NULL COMMENT 'Thời điểm Quản lý chốt nhập kho (Khóa bất biến)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_gr_code UNIQUE (receipt_code),
    CONSTRAINT fk_gr_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE RESTRICT,
    CONSTRAINT fk_gr_creator FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_gr_total CHECK (total_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Phiếu nhập kho Immutable sau khi chốt RECEIVED (Khớp Use Cases: UC22, UC23)';

CREATE TABLE goods_receipt_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    received_quantity INT NOT NULL,
    unit_cost DECIMAL(12,2) NOT NULL,
    CONSTRAINT fk_gri_receipt FOREIGN KEY (receipt_id) REFERENCES goods_receipts(id) ON DELETE CASCADE,
    CONSTRAINT fk_gri_prod FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT,
    CONSTRAINT chk_gri_qty CHECK (received_quantity > 0),
    CONSTRAINT chk_gri_cost CHECK (unit_cost >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Chi tiết mặt hàng trong phiếu nhập (Khớp Use Cases: UC22, UC23)';

CREATE TABLE stock_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    transaction_type ENUM('IMPORT','ORDER_DEDUCT','ORDER_CANCELLED_RESTOCK','MANUAL_ADJUSTMENT') NOT NULL,
    quantity_change INT NOT NULL,
    previous_stock INT NOT NULL,
    current_stock INT NOT NULL,
    reference_code VARCHAR(50) NULL,
    performed_by_user_id BIGINT NULL COMMENT 'Có thể NULL nếu job tự động chạy',
    note VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_slog_prod FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT,
    CONSTRAINT fk_slog_user FOREIGN KEY (performed_by_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_slog_stocks CHECK (previous_stock >= 0 AND current_stock >= 0),
    CONSTRAINT chk_slog_change CHECK (quantity_change <> 0),
    CONSTRAINT chk_slog_balance CHECK (current_stock = previous_stock + quantity_change)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Sổ cái biến động kho bất biến phục vụ kiểm toán & báo cáo (Khớp Use Cases: UC21, UC23, UC24, UC25, UC27)';

CREATE INDEX idx_slog_prod_time ON stock_logs(product_id, created_at);

-- -----------------------------------------------------------------------------
-- PHÂN HỆ 5: GIỎ HÀNG, ĐƠN HÀNG & THANH TOÁN (FE-3, FE-4)
-- -----------------------------------------------------------------------------

CREATE TABLE carts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_carts_user UNIQUE (user_id),
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Giỏ hàng lưu trữ liên thiết bị cho User đã đăng nhập (Khớp Use Cases: UC10)';

CREATE TABLE cart_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cart_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ci_cart FOREIGN KEY (cart_id) REFERENCES carts(id) ON DELETE CASCADE,
    CONSTRAINT fk_ci_prod FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT,
    CONSTRAINT uk_ci_cart_prod UNIQUE (cart_id, product_id),
    CONSTRAINT chk_ci_qty CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Sản phẩm trong giỏ hàng (Khớp Use Cases: UC10)';

CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_code VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    shipping_recipient_name VARCHAR(100) NOT NULL,
    shipping_phone VARCHAR(15) NOT NULL,
    shipping_full_address VARCHAR(500) NOT NULL,
    shipping_fee DECIMAL(10,2) NOT NULL,
    final_total_amount DECIMAL(12,2) NOT NULL,
    order_status ENUM('PENDING_PAYMENT','PENDING_CONFIRMATION','CONFIRMED','SHIPPING','DELIVERED','CANCELLED') NOT NULL DEFAULT 'PENDING_PAYMENT',
    payment_method ENUM('COD','VNPAY') NOT NULL,
    payment_status ENUM('UNPAID','PAID','REFUNDED') NOT NULL DEFAULT 'UNPAID',
    tracking_number VARCHAR(100) NULL,
    cancel_reason VARCHAR(255) NULL,
    
    -- Các mốc thời gian chuyển trạng thái (Thay thế bảng order_status_history)
    confirmed_at DATETIME NULL COMMENT 'Thời điểm Staff duyệt đơn',
    shipped_at DATETIME NULL COMMENT 'Thời điểm Bàn giao bưu tá',
    delivered_at DATETIME NULL COMMENT 'Thời điểm Giao thành công',
    cancelled_at DATETIME NULL COMMENT 'Thời điểm Hủy đơn',
    
    -- Dữ liệu đối soát cổng thanh toán VNPay (Thay thế bảng payment_logs)
    vnpay_txn_ref VARCHAR(100) NULL COMMENT 'Mã tham chiếu giao dịch cổng VNPay',
    vnpay_pay_date VARCHAR(20) NULL COMMENT 'Thời gian thanh toán ghi nhận từ VNPay',
    vnpay_bank_code VARCHAR(50) NULL COMMENT 'Ngân hàng thực hiện giao dịch',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_orders_code UNIQUE (order_code),
    CONSTRAINT fk_ord_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_ord_fee CHECK (shipping_fee >= 0),
    CONSTRAINT chk_ord_total CHECK (final_total_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bảng đơn hàng chính (Tích hợp Timeline & Mã đối soát VNPay) (Khớp Use Cases: UC11, UC11.1, UC12, UC12.1, UC14, UC14.1, UC26)';

CREATE INDEX idx_ord_user_time ON orders(user_id, created_at);
CREATE INDEX idx_ord_status_time ON orders(order_status, created_at);
CREATE INDEX idx_ord_vnpay_ref ON orders(vnpay_txn_ref);

CREATE TABLE order_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    product_name_snapshot VARCHAR(255) NOT NULL,
    unit_price_snapshot DECIMAL(12,2) NOT NULL,
    CONSTRAINT fk_oi_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT,
    CONSTRAINT fk_oi_prod FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT,
    CONSTRAINT chk_oi_qty CHECK (quantity > 0),
    CONSTRAINT chk_oi_price CHECK (unit_price_snapshot >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Chi tiết mặt hàng trong đơn lưu snapshot giá và tên (Khớp Use Cases: UC11, UC12, UC14)';

CREATE TABLE order_vouchers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    discount_applied DECIMAL(12,2) NOT NULL,
    CONSTRAINT fk_ov_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_ov_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers(id) ON DELETE RESTRICT,
    CONSTRAINT uk_ov_order UNIQUE (order_id) COMMENT 'Chặn cứng mỗi đơn hàng chỉ áp dụng tối đa 01 voucher duy nhất',
    CONSTRAINT chk_ov_discount CHECK (discount_applied >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Voucher áp dụng cho đơn hàng ràng buộc tối đa 1 voucher/đơn (Khớp Use Cases: UC11.2, UC13)';

-- -----------------------------------------------------------------------------
-- PHÂN HỆ 6: KIỂM TOÁN AN NINH & ĐỐI SOÁT HỆ THỐNG (FE-7)
-- -----------------------------------------------------------------------------

CREATE TABLE audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    action VARCHAR(50) NOT NULL COMMENT 'Ví dụ: UPDATE_ORDER_STATUS, VNPAY_IPN_CALLBACK, MANUAL_STOCK_ADJUST',
    target_table VARCHAR(50) NOT NULL,
    target_id BIGINT NULL,
    details_json JSON NULL COMMENT 'Lưu chi tiết payload JSON, dữ liệu trước/sau thay đổi hoặc phản hồi từ VNPay',
    ip_address VARCHAR(45) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_al_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Nhật ký kiểm toán an ninh lưu vết CRUD và đối soát giao dịch (Khớp Use Cases: UC29)';

-- =============================================================================
-- KẾT THÚC CẤU TRÚC (TỔNG: ĐÚNG 23 BẢNG | MySQL 8.0.16+ | InnoDB)
-- ĐÃ LOẠI BỎ 100% QUAN HỆ KHÓA NGOẠI VÒNG GIỮA CÁC BẢNG KHÁC NHAU (Non-cyclic inter-table dependencies)
-- (Chỉ duy nhất bảng categories có quan hệ tự tham chiếu parent_id để dựng cây danh mục cha/con)
-- =============================================================================
