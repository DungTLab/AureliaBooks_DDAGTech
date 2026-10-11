# 🔐 DANH SÁCH TÀI KHOẢN MẪU HỆ THỐNG AURELIABOOKS

> **Ghi chú quan trọng**: Hệ thống hỗ trợ đăng nhập kép (**Dual-Identifier Authentication**): Bạn có thể đăng nhập bằng **Email** hoặc **Số điện thoại**.

---

## 📋 1. Bảng Tổng Hợp Tài Khoản Theo Phân Quyền (RBAC)

| Vai trò (Role) | Email đăng nhập | Số điện thoại | Mật khẩu | Họ và tên | Chuyển hướng sau đăng nhập | Quyền hạn chính |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **ADMIN** (Quản trị viên) | `admin@aureliabook.vn` | `0988888888` | `Admin@123` | Quản Trị Viên (Admin) | `/dashboard` | Toàn quyền quản trị hệ thống, phân quyền người dùng, cấu hình. |
| **MANAGER** (Quản lý) | `manager@aureliabook.vn` | `0977777777` | `Manager@123` | Nguyễn Quản Lý (Manager) | `/dashboard` | Quản lý Nhà cung cấp (UC30), Phê duyệt nhập kho (UC23), Quản lý sản phẩm/danh mục. |
| **SALE_STAFF** (Nhân viên bán hàng) | `staff@aureliabook.vn` | `0966666666` | `Staff@123` | Trần Thu Ngân (Sale Staff) | `/dashboard` | Lập đơn nhập kho bản nháp (DRAFT), xử lý đơn hàng bán lẻ. |
| **CUSTOMER** (Khách hàng) | `customer@aureliabook.vn` | `0944444444` | `Customer@123` | Lê Khách Hàng (Customer) | `/` (Trang chủ) | Xem sản phẩm, giỏ hàng, đặt hàng, quản lý đơn cá nhân. |

---

## 👥 2. Các Tài Khoản Mẫu Bổ Sung (Dự Phòng / Test)

| Vai trò (Role) | Email đăng nhập | Số điện thoại | Mật khẩu | Họ và tên |
| :--- | :--- | :--- | :--- | :--- |
| **MANAGER** (Kho) | `kho.nguyen@aureliabook.vn` | `0971111111` | `Manager@123` | Nguyễn Văn Kho (Manager) |
| **SALE_STAFF** (Bán hàng) | `lan.tran@aureliabook.vn` | `0961111111` | `Staff@123` | Trần Thị Lan (Sale Staff) |
| **CUSTOMER** (Cá nhân) | `anhntdce200031@gmail.com` | `0955555555` | `Customer@123` | Lê Khách Hàng (Customer) |

---

## 🚀 3. Các Liên Kết Nhanh Để Kiểm Thử

* **Trang Đăng Nhập**: [http://localhost:8080/auth/login](http://localhost:8080/auth/login) (hoặc `http://localhost:8080/login`)
* **Quản Lý Nhà Cung Cấp (UC30)**: [http://localhost:8080/manager/suppliers](http://localhost:8080/manager/suppliers) *(Đăng nhập bằng tài khoản `ADMIN` hoặc `MANAGER`)*
* **Bảng Điều Khiển Quản Trị**: [http://localhost:8080/dashboard](http://localhost:8080/dashboard)
* **Trang Mua Sắm Khách Hàng**: [http://localhost:8080/](http://localhost:8080/)

---

## 🛠️ 4. Quy Ước Mật Khẩu (Security Policy)

Tất cả mật khẩu trên đều thỏa mãn quy tắc kiểm tra bảo mật nghiêm ngặt của hệ thống:
* Tối thiểu 8 ký tự.
* Có ít nhất 1 chữ hoa (`A`, `M`, `S`, `C`).
* Có ít nhất 1 chữ số (`1`, `2`, `3`).
* Có ít nhất 1 ký tự đặc biệt (`@`).
* Được băm bằng thuật toán **BCrypt** (12 rounds) trước khi lưu vào database.

File script SQL khởi tạo tài khoản tự động được đặt tại:
`docker/initdb/03_seed_users.sql`
