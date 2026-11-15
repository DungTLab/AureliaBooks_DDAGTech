# Tài khoản AureliaBooks

## Local và demo

Danh sách fixture nằm ở đầu [script seed demo](../../docker/demo/03_seed_users.sql). Dùng `/auth/login` cho Customer và `/internal/login` cho Admin, Manager và Sale Staff.

`docker compose up -d mysql-db` nạp seed demo khi tạo volume mới. Với DB dev đã tồn tại, kiểm tra xung đột rồi chạy [seed role](../../docker/initdb/02_seed_roles.sql) và [seed user demo](../../docker/demo/03_seed_users.sql) nếu cần. Seed giữ mật khẩu, role, hồ sơ và trạng thái tài khoản cũ. Xung đột định danh làm rollback giao dịch seed. Schema khởi tạo tạo lại database. Với DB đã có dữ liệu, dùng migration phù hợp.

## Bootstrap Admin production

Khi khởi tạo MySQL mới, truyền `MYSQL_ROOT_PASSWORD`, `DB_USER` và `DB_PASS` cho cấu hình triển khai.

```sh
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d mysql-db
```

Bộ file này chỉ nạp schema và các role nền tảng. Đặt biến sau trên tiến trình **Spring Boot**.

```dotenv
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_EMAIL=<email quản trị>
BOOTSTRAP_ADMIN_PASSWORD=<mật khẩu theo chính sách>
BOOTSTRAP_ADMIN_PHONE=<SĐT 10 chữ số nếu có>
```

Bootstrap khóa role Admin trong transaction, lưu hash BCrypt và ghi `ADMIN_BOOTSTRAPPED`. Chỉ tạo khi chưa có Admin nào, kể cả Admin inactive. Sau khi xác nhận đăng nhập, tắt `BOOTSTRAP_ADMIN_ENABLED` và gỡ mật khẩu bootstrap khỏi cấu hình runtime.

`AdminBootstrapMySqlTest` là test opt-in với `-Dbootstrap.mysql=true`. Dùng DB QA mới có tên bắt đầu bằng `auth_bootstrap_qa_`.
