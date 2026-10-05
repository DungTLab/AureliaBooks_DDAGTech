# Register Account — bàn giao UC05

## Phạm vi và kế hoạch nghiệm thu

1. Đối chiếu RDS và source: form đăng ký, schema users/roles, layout và màu index.
2. Triển khai DTO/controller/service đăng ký Local; validation, BCrypt và role CUSTOMER do server gán.
3. Tích hợp Google OAuth2/OpenID Connect; verified email, sub, tên/avatar, chống tạo trùng.
4. Kiểm tra unit/MVC/security, build JAR, render responsive; ghi riêng những kiểm tra chưa chạy.

Yêu cầu hiện tại của người dùng ưu tiên các chi tiết cũ trong RDS: không xét tuổi; mật khẩu không bắt buộc chữ thường, nhưng bắt buộc chữ hoa/số/ký tự đặc biệt. Không tạo giỏ hàng trong task này. UI tham chiếu bảng trường ở mục Member Registration Screen của RDS; dùng layout/base và bảng màu hiện có của index, không đổi framework CSS Tailwind của dự án. Giới tính không bắt buộc và mặc định OTHER; checkbox đồng ý điều khoản theo bảng UI RDS.

## Chạy Local

Bật Docker Desktop và chạy `docker compose up -d mysql-db`. Đợi MySQL sẵn sàng rồi chạy `mvn spring-boot:run`. Trang đăng ký: `http://localhost:8080/auth/register`.

Schema gồm 22 bảng và seed `docker/initdb/02_seed_roles.sql` đã có `ROLE_CUSTOMER`; task không sửa SQL. Nếu database thiếu role, đăng ký báo lỗi thân thiện, không tự tạo role.

## Bật Google

Đăng ký OAuth client loại Web application trong Google Cloud. Authorized redirect URI khi chạy local: `http://localhost:8080/login/oauth2/code/google` (production cần URL HTTPS tương ứng).

Thiết lập biến môi trường trước khi chạy:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'google'
$env:GOOGLE_CLIENT_ID = '<client-id của nhóm>'
$env:GOOGLE_CLIENT_SECRET = '<client-secret của nhóm>'
mvn spring-boot:run
```

Không lưu client secret trong source hoặc commit. Khi không bật profile google, đăng ký Local vẫn hoạt động; nút Google ở trạng thái chưa khả dụng. Khi bật profile google phải cung cấp đủ hai biến môi trường.

Cấu hình scope `openid,profile,email`; Spring Security xử lý authorization code và kiểm tra ID token trước khi gọi hook OIDC. Hook dùng claims được lấy server-side, kiểm tra email_verified, tìm identity GOOGLE/sub rồi mới kiểm tra email. Xem [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect).

- Identity đã tồn tại và còn hoạt động: sử dụng tài khoản cũ, không tạo thêm hoặc đổi role.
- Identity bị khóa: từ chối.
- Identity mới nhưng email thuộc tài khoản khác: từ chối tự liên kết; yêu cầu dùng phương thức đã đăng ký. Task không triển khai account linking.
- Identity mới và email chưa có: tạo một CUSTOMER, lưu sub/email/tên/avatar; password/phone/DOB để null. Không lấy dữ liệu form để giả lập Google claims.
- Unique constraints SQL giữ vai trò quyết định khi hai request đến đồng thời; lỗi trùng được chuyển thành thông báo thân thiện. Chưa chạy thử race trên MySQL thật.

## Kiểm thử và giới hạn

Xem `QA_QC.md`, `verification-results.json`, `browser-results.json` và ảnh `register-375.png`, `register-768.png`, `register-1440.png`. Chạy lại Maven để tạo báo cáo kiểm thử tại `target/surefire-reports`.

Luồng đăng ký Local thành công chuyển về trang đăng nhập và hiện toast. Trang đăng nhập hiện vẫn là khung của task Login; task này không triển khai giao diện Login, quên mật khẩu, liên kết tài khoản hoặc nghiệp vụ checkout.

Chưa chạy đăng ký thật trên MySQL hoặc Google Cloud: Docker daemon không hoạt động và localhost:3306 không mở trong phiên kiểm tra. Các test service dùng mock repository; test Google userinfo dùng HTTP giả lập; test SecurityFilterChain dùng MockMvc với profile YAML thật và client test. Browser QA dùng HTML do Thymeleaf/MockMvc render, không thay thế kiểm thử submit trên server có database.
