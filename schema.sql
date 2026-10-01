-- =============================================================================
-- E-COMMERCE DATABASE SYSTEM FOR BOOKSTORE (AURELIABOOK)
-- Database Engine: MySQL 8.0.16+ InnoDB | Charset: utf8mb4 | Collation: utf8mb4_0900_ai_ci
-- Scale: 23 Normalized Tables
-- 
-- KEY ARCHITECTURAL STREAMLINING DECISIONS:
-- 1. Dropped user_vouchers table: Transitioned to manual public voucher code entry,
--    preventing abuse via real-time SELECT queries against orders and order_vouchers tables.
-- 2. Dropped inventory_reservations table: Transitioned to Stock Pre-check -> Actual Payment
--    -> Success workflow with atomic JPA update to deduct stock (preventing negative inventory).
-- 3. Dropped ai_recommendations table: Replaced by in-memory Spring AI SimpleVectorStore,
--    storing semantic vector embeddings in the embedding (JSON) column of products table.
-- 4. Dropped order_status_history table: Query status progression timestamps directly
--    from orders table (created_at, confirmed_at, shipped_at, delivered_at, cancelled_at) and audit_logs.
-- 5. Dropped payment_logs table: Merged VNPay reconciliation reference into orders table (vnpay_txn_ref),
--    storing full raw callback JSON in audit_logs.
-- 6. Dropped book_series table: Merged into series_name column in books table as MVP does not sell bundled combos.
-- 
-- USE CASE TRACEABILITY MATRIX (100% COVERAGE):
-- - Subsystem 1 (Accounts & Addresses): roles, users, user_roles, shipping_addresses -> UC05, UC06, UC07, UC08, UC09, UC28
-- - Subsystem 2 (Promotions): vouchers, order_vouchers -> UC11.2, UC13
-- - Subsystem 3 (Catalog & AI): categories, products, publishers, books, authors, book_authors, brands, stationeries -> UC01, UC02, UC03, UC04, UC15, UC16, UC17, UC18, UC19, UC20, UC21
-- - Subsystem 4 (Inbound Logistics & Inventory): suppliers, goods_receipts, goods_receipt_items, stock_logs -> UC21, UC22, UC23, UC24, UC25, UC27, UC30
-- - Subsystem 5 (Cart, Orders & Payments): carts, cart_items, orders, order_items, order_vouchers -> UC10, UC11, UC11.1, UC12, UC12.1, UC14, UC14.1, UC26
-- - Subsystem 6 (Security & Audit): audit_logs -> UC29
-- =============================================================================

DROP DATABASE IF EXISTS aurelia_books_db;
CREATE DATABASE aurelia_books_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE aurelia_books_db;

-- -----------------------------------------------------------------------------
-- SUBSYSTEM 1: ACCOUNTS, AUTHORIZATION & ADDRESSES (FE-1)
-- -----------------------------------------------------------------------------

CREATE TABLE roles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL,
    description VARCHAR(255) NULL,
    CONSTRAINT uk_roles_name UNIQUE (role_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='System RBAC roles catalog (Maps to Use Cases: UC06, UC28)';

CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NULL COMMENT 'BCrypt hash (Nullable for Google OAuth2 SSO accounts)',
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(15) NULL COMMENT 'Nullable upon Google SSO registration; required before checkout',
    dob DATE NULL COMMENT 'Nullable to streamline initial customer registration',
    gender ENUM('MALE','FEMALE','OTHER') NOT NULL DEFAULT 'OTHER',
    avatar_url VARCHAR(500) NULL COMMENT 'Profile picture URL from Google OAuth2 or local storage',
    auth_provider ENUM('LOCAL','GOOGLE') NOT NULL DEFAULT 'LOCAL' COMMENT 'Authentication provider (LOCAL or GOOGLE)',
    provider_id VARCHAR(100) NULL COMMENT 'Unique identifier from Google IdP (OpenID Connect sub claim)',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User and staff accounts supporting Local Auth and Google OAuth2 SSO (Maps to Use Cases: UC05, UC06, UC07, UC08, UC28)';

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Many-to-Many junction table between Users and Roles (Maps to Use Cases: UC06, UC28)';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Customer shipping address book (Maps to Use Cases: UC09, UC11)';

-- -----------------------------------------------------------------------------
-- SUBSYSTEM 2: PROMOTIONS & PUBLIC VOUCHERS (FE-3)
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
    usage_limit INT NOT NULL DEFAULT 100 COMMENT 'Remaining system-wide usage quota (decremented upon confirmed order)',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Public discount vouchers configured by Store Managers (Maps to Use Cases: UC11.2, UC13)';

-- -----------------------------------------------------------------------------
-- SUBSYSTEM 3: CATALOG & PRODUCTS (IS-A ARCHITECTURE + VECTOR AI) (FE-2, FE-6)
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Self-referencing hierarchical product categories (Maps to Use Cases: UC01, UC20)';

CREATE TABLE products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    category_id BIGINT NOT NULL,
    product_type ENUM('BOOK','STATIONERY') NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    original_cost DECIMAL(12,2) NOT NULL,
    weight_grams INT NOT NULL DEFAULT 200,
    stock_quantity INT NOT NULL DEFAULT 0 COMMENT 'Available to Promise (ATP) inventory count for sales',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    main_image_url VARCHAR(500) NULL,
    tags VARCHAR(255) NULL COMMENT 'Classification tags (e.g., psychology, skills, business)',
    embedding JSON NULL COMMENT 'Multidimensional semantic vector embedding for Spring AI SimpleVectorStore',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Base table for Books and Stationeries with Vector AI integration (Maps to Use Cases: UC01, UC02, UC03, UC04, UC15, UC16, UC21)';

CREATE TABLE publishers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    address VARCHAR(255) NULL,
    email VARCHAR(100) NULL,
    CONSTRAINT uk_publishers_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Book publishers catalog (Maps to Use Cases: UC03, UC18)';

CREATE TABLE books (
    product_id BIGINT PRIMARY KEY,
    isbn VARCHAR(20) NOT NULL,
    publisher_id BIGINT NOT NULL,
    series_name VARCHAR(150) NULL COMMENT 'Series/Collection title for UI badge display (replaces book_series table)',
    volume_number INT NULL COMMENT 'Volume order sequence within the series',
    is_textbook BOOLEAN NOT NULL DEFAULT FALSE,
    publication_year INT NULL,
    edition VARCHAR(50) NULL,
    page_count INT NULL,
    language VARCHAR(50) NOT NULL DEFAULT 'Tiếng Việt',
    cover_type ENUM('PAPERBACK','HARDCOVER') NOT NULL DEFAULT 'PAPERBACK',
    CONSTRAINT uk_books_isbn UNIQUE (isbn),
    CONSTRAINT fk_book_prod FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT fk_book_pub FOREIGN KEY (publisher_id) REFERENCES publishers(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Book entity (IS-A Product - Managed by Series/Volume) (Maps to Use Cases: UC01, UC02, UC03, UC15)';

CREATE TABLE authors (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    biography TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Book authors catalog (Maps to Use Cases: UC03, UC17)';

CREATE TABLE book_authors (
    book_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    PRIMARY KEY (book_id, author_id),
    CONSTRAINT fk_ba_book FOREIGN KEY (book_id) REFERENCES books(product_id) ON DELETE CASCADE,
    CONSTRAINT fk_ba_author FOREIGN KEY (author_id) REFERENCES authors(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Many-to-Many junction between Books and Authors (Maps to Use Cases: UC03, UC15, UC17)';

CREATE TABLE brands (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    origin_country VARCHAR(100) NULL,
    CONSTRAINT uk_brands_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Stationery manufacturer brands (Maps to Use Cases: UC03, UC19)';

CREATE TABLE stationeries (
    product_id BIGINT PRIMARY KEY,
    brand_id BIGINT NOT NULL,
    material VARCHAR(100) NULL,
    color VARCHAR(50) NULL,
    warranty_months INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_stat_prod FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT fk_stat_brand FOREIGN KEY (brand_id) REFERENCES brands(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Stationery entity (IS-A Product) (Maps to Use Cases: UC01, UC02, UC03, UC16)';

-- -----------------------------------------------------------------------------
-- SUBSYSTEM 4: INBOUND GOODS RECEIPTS & AUDITABLE INVENTORY (FE-5)
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Merchandise suppliers catalog (Maps to Use Cases: UC22, UC30)';

CREATE TABLE goods_receipts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_code VARCHAR(32) NOT NULL,
    supplier_id BIGINT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    status ENUM('DRAFT','RECEIVED') NOT NULL DEFAULT 'DRAFT',
    total_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    note VARCHAR(500) NULL,
    received_at DATETIME NULL COMMENT 'Timestamp when Manager finalizes receipt (Immutable lock)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_gr_code UNIQUE (receipt_code),
    CONSTRAINT fk_gr_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE RESTRICT,
    CONSTRAINT fk_gr_creator FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_gr_total CHECK (total_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Inbound goods receipts becoming immutable once marked RECEIVED (Maps to Use Cases: UC22, UC23)';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Line items contained in an inbound goods receipt (Maps to Use Cases: UC22, UC23)';

CREATE TABLE stock_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    transaction_type ENUM('IMPORT','ORDER_DEDUCT','ORDER_CANCELLED_RESTOCK','MANUAL_ADJUSTMENT') NOT NULL,
    quantity_change INT NOT NULL,
    previous_stock INT NOT NULL,
    current_stock INT NOT NULL,
    reference_code VARCHAR(50) NULL,
    performed_by_user_id BIGINT NULL COMMENT 'Nullable if executed by automated background job',
    note VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_slog_prod FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT,
    CONSTRAINT fk_slog_user FOREIGN KEY (performed_by_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_slog_stocks CHECK (previous_stock >= 0 AND current_stock >= 0),
    CONSTRAINT chk_slog_change CHECK (quantity_change <> 0),
    CONSTRAINT chk_slog_balance CHECK (current_stock = previous_stock + quantity_change)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable stock ledger for inventory auditing and accounting (Maps to Use Cases: UC21, UC23, UC24, UC25, UC27)';

CREATE INDEX idx_slog_prod_time ON stock_logs(product_id, created_at);

-- -----------------------------------------------------------------------------
-- SUBSYSTEM 5: CART, ORDERS & PAYMENTS (FE-3, FE-4)
-- -----------------------------------------------------------------------------

CREATE TABLE carts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_carts_user UNIQUE (user_id),
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Cross-device persistent shopping cart for authenticated users (Maps to Use Cases: UC10)';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Line items stored within shopping carts (Maps to Use Cases: UC10)';

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
    
    -- Status progression milestones (Replaces order_status_history table)
    confirmed_at DATETIME NULL COMMENT 'Timestamp when staff confirms order',
    shipped_at DATETIME NULL COMMENT 'Timestamp when handed over to courier',
    delivered_at DATETIME NULL COMMENT 'Timestamp when customer successfully receives order',
    cancelled_at DATETIME NULL COMMENT 'Timestamp when order is cancelled',
    
    -- VNPay payment gateway reconciliation fields (Replaces payment_logs table)
    vnpay_txn_ref VARCHAR(100) NULL COMMENT 'VNPay transaction reference identifier',
    vnpay_pay_date VARCHAR(20) NULL COMMENT 'Payment execution timestamp from VNPay',
    vnpay_bank_code VARCHAR(50) NULL COMMENT 'Issuing bank code used for transaction',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_orders_code UNIQUE (order_code),
    CONSTRAINT fk_ord_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_ord_fee CHECK (shipping_fee >= 0),
    CONSTRAINT chk_ord_total CHECK (final_total_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Primary orders table (Incorporates lifecycle timeline and VNPay reconciliation) (Maps to Use Cases: UC11, UC11.1, UC12, UC12.1, UC14, UC14.1, UC26)';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Order line items preserving point-in-time product name and price snapshots (Maps to Use Cases: UC11, UC12, UC14)';

CREATE TABLE order_vouchers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    discount_applied DECIMAL(12,2) NOT NULL,
    CONSTRAINT fk_ov_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_ov_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers(id) ON DELETE RESTRICT,
    CONSTRAINT uk_ov_order UNIQUE (order_id) COMMENT 'Hard constraint enforcing maximum 1 voucher per order',
    CONSTRAINT chk_ov_discount CHECK (discount_applied >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Applied vouchers per order enforced at maximum 1 voucher per order (Maps to Use Cases: UC11.2, UC13)';

-- -----------------------------------------------------------------------------
-- SUBSYSTEM 6: SECURITY AUDIT & SYSTEM RECONCILIATION (FE-7)
-- -----------------------------------------------------------------------------

CREATE TABLE audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    action VARCHAR(50) NOT NULL COMMENT 'e.g., UPDATE_ORDER_STATUS, VNPAY_IPN_CALLBACK, MANUAL_STOCK_ADJUST',
    target_table VARCHAR(50) NOT NULL,
    target_id BIGINT NULL,
    details_json JSON NULL COMMENT 'Stores raw JSON payload, before/after state diff, or VNPay IPN response',
    ip_address VARCHAR(45) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_al_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Security audit log tracking CRUD events and transaction reconciliations (Maps to Use Cases: UC29)';

-- =============================================================================
-- END OF SCHEMA (TOTAL: EXACTLY 23 TABLES | MySQL 8.0.16+ | InnoDB)
-- Non-cyclic inter-table foreign key dependencies guaranteed across all tables.
-- (Only categories table possesses a self-referencing parent_id hierarchy)
-- =============================================================================
