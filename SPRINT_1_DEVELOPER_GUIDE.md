# AURELIABOOK — SPRINT 1 DEVELOPER KICKOFF RUNBOOK
**Dự án:** AureliaBook (SWP391 - Fall 2026 | FPT University)  
**Tài liệu dành cho:** 5 thành viên nhóm phát triển (Dũng, Duy, Đức Anh, Trọng, Giác)  
**Thời gian Sprint 1:** Tuần 1 - Tuần 2 (37 Story Points)

---

## 1. KHỞI ĐỘNG HẠ TẦNG CHUNG TRÊN MÁY CÁ NHÂN (1 LẦN DUY NHẤT)

Mỗi thành viên chỉ cần mở Terminal tại thư mục `Project/AureliaBooks` và thực hiện:

### Bước 1: Khởi động CSDL MySQL 8.0 qua Docker
```bash
docker compose up -d
```
> *Container sẽ tự động khởi tạo database `bookstore_ecommerce_v2_db` và nạp toàn bộ 23 bảng SQL cùng 4 vai trò RBAC (`ADMIN`, `MANAGER`, `SALE_STAFF`, `CUSTOMER`) từ thư mục `docker/initdb/`.*

### Bước 2: Khởi động ứng dụng Spring Boot
```bash
# Trên Windows PowerShell:
.\mvnw.cmd spring-boot:run

# Hoặc trên Linux/macOS:
./mvnw spring-boot:run
```
Truy cập trình duyệt: `http://localhost:8080` để thấy trang chủ Storefront với Bootstrap 5 Navbar, Footer và Flash Alerts hoạt động mượt mà.

---

## 2. QUY CHUẨN LÀM VIỆC VỚI GIT (BRANCHING STRATEGY)

Để **tuyệt đối không bao giờ bị conflict code (xung đột mã nguồn)**:
1. **Tuyệt đối KHÔNG code trực tiếp trên nhánh `main` hay `develop`.**
2. Trước khi làm việc, luôn pull code mới nhất:
   ```bash
   git checkout develop
   git pull origin develop
   ```
3. Tạo nhánh riêng theo định dạng: `feature/<Mã_Task>-<ten-ngan-gon>`:
   ```bash
   git checkout -b feature/UC05-register
   ```
4. Khi làm xong, commit rõ ràng và tạo Pull Request (PR) vào nhánh `develop`:
   ```bash
   git add .
   git commit -m "feat(auth): implement UC05 account registration with validation"
   git push origin feature/UC05-register
   ```
5. **Quy tắc nghiệm thu PR:** Cần ít nhất 1 thành viên review code và bấm `Approve` thì mới được merge vào `develop`.

---

## 3. PHÂN CÔNG TÁC VỤ CỤ THỂ CHO TỪNG THÀNH VIÊN TRONG SPRINT 1

### 👤 Lê Tiến Dũng (Team Lead & Auth)
* **Nhiệm vụ:** `UC05` (Đăng ký tài khoản) & `UC06` (Đăng nhập cơ sở).
* **Nhánh làm việc:** `feature/UC05-register-auth`
* **Các file cần code:**
  - `dto/request/RegisterRequest.java` (Validate: email `@Email`, mật khẩu >= 8 ký tự, SĐT định dạng VN).
  - `service/AuthService.java` & `service/impl/AuthServiceImpl.java` (Kiểm tra trùng email/phone, hash mật khẩu bằng `PasswordEncoder`, gán role `ROLE_CUSTOMER`).
  - `controller/AuthController.java` (Mapping `/auth/register` hiển thị `register.html`).
  - `templates/auth/register.html` (Form đăng ký nhúng fragment `layout/base :: layout`).

### 👤 Huỳnh Nhật Duy (Catalog & Storefront)
* **Nhiệm vụ:** `UC01` (Duyệt danh mục), `UC02` (Tìm kiếm sản phẩm), `UC03` (Xem chi tiết Sách & VPP).
* **Nhánh làm việc:** `feature/UC01-storefront-catalog`
* **Các file cần code:**
  - `repository/ProductRepository.java` (JPA Specification lọc theo category, khoảng giá, phân trang).
  - `service/ProductService.java` & `service/impl/ProductServiceImpl.java`.
  - `controller/ProductController.java` (Mapping `/products`, `/products/{id}`, `/products/search`).
  - `templates/product/catalog.html` & `product-detail.html` (Hiển thị thẻ Card sản phẩm, giá bán, tồn kho).

### 👤 Nguyễn Trần Đức Anh (Warehouse & Inventory)
* **Nhiệm vụ:** `UC22` (Lập phiếu nhập kho DRAFT), `FND-03` (Stock Ledger Service).
* **Nhánh làm việc:** `feature/UC22-goods-receipt`
* **Các file cần code:**
  - `entity/GoodsReceipt.java`, `GoodsReceiptItem.java`, `StockMovementLog.java`.
  - `repository/GoodsReceiptRepository.java`.
  - `service/StockLedgerService.java` (Ghi thẻ kho bất biến: IMPORT, EXPORT).
  - `controller/admin/AdminGoodsReceiptController.java` (Mapping `/admin/receipts/create`).
  - `templates/admin/goods-receipt-form.html`.

### 👤 Trần Huỳnh Giác (Security & Exception Lead)
* **Nhiệm vụ:** `FND-04` (Spring Security Filter Chain & RBAC), `UC29` (AOP Audit Log).
* **Nhánh làm việc:** `feature/FND-04-security-rbac`
* **Các file cần code:**
  - `security/CustomUserDetails.java` & `security/CustomUserDetailsService.java` (Nạp thông tin user từ DB).
  - Cấu hình phân quyền truy cập URL theo Role trong `config/SecurityConfig.java`.
  - `config/AuditLogAspect.java` (AOP tự động ghi nhật ký vào bảng `audit_logs`).

### 👤 Nguyễn Phú Trọng (Order Prep & Peer QA)
* **Nhiệm vụ:** Phụ trách Test chéo toàn bộ các tính năng của Dũng và Duy; chuẩn bị Entity Đơn hàng (`Order`, `OrderItem`).
* **Nhánh làm việc:** `feature/UC10-cart-order-prep`
* **Các file cần code:**
  - Thiết kế kịch bản test chi tiết theo cột **QA Checklist** trong file Excel.
  - Tạo `entity/Cart.java`, `CartItem.java`, `Order.java`, `OrderItem.java`.
  - Viết Unit Test cho Service đăng ký và Service danh mục sản phẩm.

---

## 4. QUY TẮC BẢO TOÀN KIẾN TRÚC
* Đọc kỹ file [PACKAGE_DICTIONARY.md](file:///d:/FPTU_document/SE_5/SWP391/Project/PACKAGE_DICTIONARY.md) trước khi tạo file mới.
* Tầng Controller **CHỈ ĐƯỢC PHÉP** gọi Service Interface, không được gọi trực tiếp Repository.
* Không truyền trực tiếp `Entity` ra ngoài View mà phải bọc qua DTO / ViewModel.
