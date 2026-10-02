# Nhận việc Sprint 1

Đây là khung để nhóm tự triển khai. Controller mới chỉ có GET mở trang chờ; chưa có POST/PUT/DELETE. DTO chưa có validation hoàn chỉnh; service chỉ là interface. Security extension points và audit aspect là abstract, chưa được đăng ký bean. Không có luồng đăng ký, OAuth, CRUD, nhập kho hoặc audit hoàn chỉnh.

## Theo người phụ trách

| Người | Task trong roadmap | Điểm bắt đầu |
|---|---|---|
| Lê Tiến Dũng | FND-01, UC05–09 | AuthController, ProfileController, UserService, AddressService, security/Custom*UserService, DTO auth/profile, templates/auth và profile |
| Huỳnh Nhật Duy | FND-02, UC01–03, UC15–20, POLISH-01 | ProductSpecification, PageableUtils, ProductService, AdminProductService, MasterDataService, controller catalog/danh mục, templates/product và admin |
| Nguyễn Trần Đức Anh | FND-03, UC30, UC22/23/21 | StockLedgerService, GoodsReceiptService, SupplierService, entity nhập kho, controller supplier/receipt |
| Trần Huỳnh Giác | FND-04, UC28/29 | SecurityConfig, security handlers, AdminUserService, AuditLogService, AuditLogAspect, controller admin user/audit |
| Nguyễn Phú Trọng | QA chéo Sprint 1; cart bắt đầu Sprint 2 | Kiểm tra receipt/stock theo roadmap; chưa tạo CartService/checkout/voucher trong khung này |

Nguồn cụ thể: `5_Week_Sprint_Roadmap!A8:P21` trong workbook kế hoạch. Các lớp response là hợp đồng ban đầu; nhóm bổ sung response chi tiết/mapper có kiểu rõ ràng trước khi nối controller. `service/impl` vẫn để nhóm viết implementation và transaction. Không serialize entity ra client, không dùng Object/Map thay DTO nghiệp vụ.

## Đồng bộ SQL mới

- `User.role` là một Role bắt buộc qua `users.role_id`; không có bảng nối user-role. UC05 tự gán Customer ở server, UC28 nhận một `roleId`; principal ánh xạ đúng một authority.
- `StockMovementLog` giữ tên lớp theo workbook nhưng map bảng `stock_logs`: `quantity_change`, `previous_stock`, `current_stock`, `performed_by_user_id`; enum là IMPORT, ORDER_DEDUCT, ORDER_CANCELLED_RESTOCK, MANUAL_ADJUSTMENT. Service phải kiểm tra cân bằng và ghi cùng transaction cập nhật tồn; JPA mapping không tự đảm bảo bất biến.
- Product có trường JSON `embedding` đúng SQL; khung không tạo engine AI.
- `ddl-auto: validate` giữ SQL làm nguồn schema. Docker bootstrap dùng `docker/initdb/01_schema.sql` (22 bảng); không thêm Flyway chạy song song hoặc thực thi DROP DATABASE từ ứng dụng.
- Voucher/order thuộc Sprint 2: `order_vouchers.user_id` là người dùng voucher, phải trùng chủ đơn; unique(user_id,voucher_id) và unique(order_id). Tạo record tại đặt đơn; hủy hợp lệ phải xóa redemption, hoàn quota và audit cùng transaction. Chỉ đổi trạng thái đơn không giải phóng lượt dùng.

## Những điểm workbook còn lệch

- Workbook ghi 23 bảng, user_roles, stock_movement_logs: dùng tên và 22 bảng trong SQL mới.
- Supplier workbook đề cập MST/unique tên: SQL hiện không có MST hoặc unique tên; khung không tự thêm chúng. Chốt lại trước khi triển khai validation tương ứng.
- Login yêu cầu failed attempts/locked_until: SQL chưa có cột này; nhóm chọn nơi lưu trạng thái khóa hoặc đề xuất migration riêng. Không thêm trường JPA không có trong DDL.
- Roadmap hàng 11/12 có DoD/quality gate bị tráo giữa nhập kho, đăng ký và low-stock. Không dùng các DoD nhầm này để tuyên bố hoàn tất tính năng.
- Project đã chuyển sang Spring Boot 3.5.16 với Java 21 và dependency Security 6 do Boot quản lý. Layout Thymeleaf dùng Tailwind theo yêu cầu giao diện responsive đã chốt; Bootstrap 5 không còn là điều kiện nghiệm thu.
- Class controller/admin giữ đúng tên phân công; namespace trang quản lý sản phẩm/danh mục/NCC là `/manager/**`, lập phiếu là `/staff/receipts/new`, duyệt phiếu là `/manager/receipts`, tài khoản và audit là `/admin/**`. Chúng khớp phân vùng SecurityConfig hiện tại. Nghiệp vụ/POST về sau cần phân quyền theo thao tác.

## Cách triển khai tiếp

1. Mỗi người tạo implementation cho service mình, thêm validation, mapper và truy vấn cần thiết; nối Model vào GET shell.
2. Thêm form và mutation endpoints có CSRF, xử lý lỗi, ownership và phân quyền. Trang hiện tại không có nút submit hoặc dữ liệu mẫu giả.
3. Hoàn thiện provider/handler security trước khi test đăng nhập. OAuth lớp khung dùng OAuth2UserService; nếu chọn Google OIDC, bổ sung OidcUserService đúng principal, không chỉ wire lớp OAuth2 này.
4. Tự viết test tình huống hợp lệ/lỗi theo roadmap; không coi khung đã đạt feature DoD hay coverage.

## Chạy local

Java 21. Dùng Maven wrapper hoặc Maven đã cài: `mvn test-compile`, `mvn test`. Với DB local mới: `docker compose up -d`, chờ MySQL healthy rồi chạy ứng dụng. Cấu hình DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASS qua môi trường.

Docker chỉ nạp init script khi volume DB trống. Volume cũ cần migration riêng cho role/voucher; không xóa volume hoặc chạy bootstrap phá dữ liệu để cập nhật schema. Khung không migrate DB hiện hữu.
