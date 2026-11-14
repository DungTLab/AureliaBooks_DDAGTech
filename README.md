# AureliaBooks

Ứng dụng bán sách và văn phòng phẩm của nhóm DDAGTech. Khách mua hàng ở storefront. Admin, Manager và Sale Staff dùng cổng nội bộ để quản lý theo vai trò.

**Stack** · Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA, MySQL 8.0, Thymeleaf, Tailwind CSS và Maven.

## Chạy local

Cài JDK 21 và Docker Compose. Chạy từ thư mục gốc repo.

```powershell
docker compose up -d mysql-db
$env:DB_USER = 'app_user'
$env:DB_PASS = 'app_password'
.\mvnw.cmd spring-boot:run
```

Trên Linux/macOS, truyền cùng hai biến môi trường cho `./mvnw spring-boot:run`. MySQL mặc định ở `localhost:3306`, database `aurelia_books_db`. Nếu dùng DB khác, đặt `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASS` cho tiến trình Java.

- [Cửa hàng](http://localhost:8080/) và [đăng nhập khách hàng](http://localhost:8080/auth/login).
- [Cổng nội bộ](http://localhost:8080/internal/login) dành cho Admin, Manager và Sale Staff.
- Compose nạp seed demo khi MySQL khởi tạo **volume mới**. Xem [tài khoản demo và cách dùng DB đã có](docs/accounts/ACCOUNTS.md).

## Cấu hình tích hợp

| Tính năng | Cấu hình |
|---|---|
| Google Sign-In | Bật profile `google`, đặt `GOOGLE_CLIENT_ID` và `GOOGLE_CLIENT_SECRET`. Khai báo callback `/login/oauth2/code/google` trên đúng URL môi trường. |
| Email và OTP demo | Chạy `docker compose --profile mail up -d mailpit` để dùng SMTP local cổng 1025 và xem thư ở cổng 8025. Bật profile `sms-demo` và đặt `DEMO_SMS_INBOX` để nhận OTP SĐT qua hộp thư thử. Với SMTP riêng, đặt các biến `MAIL_*`. |
| VNPay Sandbox | Đặt `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET` và `VNPAY_RETURN_URL` cho Spring Boot. Đăng ký IPN công khai tại `/api/v1/payments/vnpay-ipn`. |
| Ảnh và R2 | Xem [runbook R2](docs/storage/R2_PRODUCT_IMAGES.md). Khi R2 tắt, avatar lưu local ở `AVATAR_DIRECTORY`. Ảnh sản phẩm tải từ form dùng cấu hình upload R2. |

Xem [.env.example](.env.example) và [application.yaml](src/main/resources/application.yaml). Khi chạy Maven hoặc IntelliJ, truyền biến cho tiến trình Java. Giữ secret thật ngoài Git.

## Cấu trúc repo

```text
src/main/java/      Java theo tầng controller, service, repository, entity và security
src/main/resources/
  templates/        View Thymeleaf theo chức năng
    internal/       View dùng chung ở cổng quản lý, gồm báo cáo analytics
    admin/          Các view quản trị hiện có
    manager/        View dành cho Manager, gồm voucher
  static/           CSS, JavaScript, ảnh và font
src/test/java/      Test theo lớp và chức năng
database/seeds/     Dữ liệu danh mục đầu vào
docker/             Schema khởi tạo, demo seed và migration
deployment/         Tệp cấu hình phục vụ triển khai
scripts/            Lệnh hỗ trợ dev, seed và kiểm thử
docs/               Hướng dẫn vận hành, nghiệp vụ và tư liệu lưu trữ
.github/workflows/  Kiểm thử, đóng gói image và triển khai
```

[Chỉ mục tài liệu](docs/README.md) · [Phân công chuẩn hóa code](docs/maintenance/code-cleanup.md)

## Kiểm thử và triển khai

Chạy `./mvnw -B -ntp verify` trên Linux/macOS hoặc `.\mvnw.cmd -B -ntp verify` trên Windows. Một số test runtime config cần Linux và quyền tệp POSIX. Các test MySQL tích hợp dùng DB QA riêng và bật theo cờ của từng suite.

Production dùng pipeline GitHub Actions và cấu hình runtime riêng. Khi khởi tạo MySQL production bằng Compose, chỉ định rõ `docker-compose.yml` và `docker-compose.prod.yml` để loại override demo. DB đã tồn tại cập nhật qua migration. Xem [runbook cấu hình runtime](docs/deployment/RUNTIME_CONFIG_SYNC.md) và [bootstrap Admin](docs/accounts/ACCOUNTS.md#bootstrap-admin-production).
