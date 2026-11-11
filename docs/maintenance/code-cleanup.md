# Phân công chuẩn hóa code AureliaBooks

Nhánh tích hợp `DungLT-Repository-Cleanup`. Đây là đề xuất phân công theo phần việc đã biết. Mỗi thành viên mở PR con vào nhánh tích hợp.

| Phụ trách | Phạm vi Java và view | Kiểm thử khi bàn giao | Reviewer đề xuất |
|---|---|---|---|
| Lê Tiến Dũng | Authentication, Profile, địa chỉ và voucher. Các view `auth/`, `profile/`, `manager/vouchers/`. Cấu hình bảo mật, layout và fragment dùng chung. | Đăng ký, đăng nhập theo cổng, OTP, phân quyền, địa chỉ, áp hai loại voucher. | Giác |
| Huỳnh Nhật Duy | Catalog, tìm kiếm, chi tiết sản phẩm và master data. Các view `product/`, `admin/products/`, `admin/catalog/`, `admin/categories/`, `admin/master_data/`. | Lọc, tìm kiếm, chi tiết, CRUD danh mục, thêm/sửa và tải ảnh sản phẩm. | Đức Anh |
| Nguyễn Phú Trọng | Giỏ, checkout, đặt đơn, xử lý đơn và trả hàng. Các view `cart/`, `checkout/`, `orders/` và view xử lý đơn của nhân viên. | Sửa giỏ, COD, checkout, thay đổi giá/tồn, hủy và trả hàng. | Dũng |
| Giác (`GiacCoBu`) | VNPay, IPN, job đối soát, tài khoản nội bộ và audit. Các view `payment/`, `admin/users/`, audit và phần bảo mật nội bộ chuyên biệt. | Callback, phân quyền Admin, đối soát, xử lý lặp và hoàn tồn. | Trọng |
| Nguyễn Trần Đức Anh | Báo cáo doanh thu, Excel, tồn kho, chứng từ nhập và nhà cung cấp. Các view `internal/analytics/`, `admin/stock/`, `admin/receipts/`, `admin/suppliers/` và Java tương ứng. | Render báo cáo theo vai trò, mở file Excel, nhập hàng, ghi nhận tồn, cảnh báo và CRUD nhà cung cấp. | Duy |

**Phối hợp file dùng chung.** Trọng phụ trách format checkout, Dũng review tích hợp voucher. Dũng phụ trách format cấu hình bảo mật, Giác review ranh giới nội bộ và callback. Đức Anh phụ trách phần kho, Trọng review thao tác trừ và hoàn tồn của đơn. Mỗi file dùng chung chỉ do một PR chỉnh định dạng tại một thời điểm.

## Quy ước dùng chung

- **Java.** Dùng bốn dấu cách theo EditorConfig. Chọn tên thể hiện trách nhiệm, dùng import tường minh và xuống dòng các chữ ký dài. Giữ validation, transaction và thứ tự lock trong commit format.
- **Thymeleaf.** Dùng bốn dấu cách theo cấp thẻ, xuống dòng nhóm thuộc tính dài. Giữ nguyên `th:*`, `sec:*`, URL, fragment, ID và `data-*` mà JavaScript đọc. Kiểm tra khoảng trắng ở đoạn có text inline hoặc CSS `white-space`.
- **Comment.** Dùng tiếng Anh ngắn gọn cho comment code. Ghi lý do của quyết định khó suy ra, chẳng hạn thứ tự lock, snapshot giá hoặc xử lý dữ liệu cũ. Dùng Javadoc cho hợp đồng phương thức và điều kiện gọi có ảnh hưởng tới dữ liệu.
- **Refactor và file mẫu.** Tách commit di chuyển, format và thay đổi cấu trúc logic. Chỉ xóa file mẫu sau khi tra route, tên view, fragment, selector JavaScript, test và script. Với refactor, nêu hành vi cần bảo toàn và test đi kèm.

## Bàn giao

1. Cập nhật từ nhánh tích hợp, giới hạn PR theo phạm vi và liệt kê file di chuyển hoặc xóa.
2. Chạy `git diff --check` và suite liên quan. Với view, thử luồng chính, form lỗi và phân quyền khi có hiển thị theo role.
3. Ghi lệnh và kết quả kiểm thử trong PR. Reviewer kiểm tra ranh giới nghiệp vụ, route, DOM cần cho JavaScript và tính toàn vẹn dữ liệu trước khi hợp nhất.
