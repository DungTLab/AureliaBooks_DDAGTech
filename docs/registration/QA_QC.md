# QA/QC — Register Account (UC05)

Ngày kiểm tra lại trước khi commit: 05/10/2026. Source đã triển khai; chưa nghiệm thu tích hợp với MySQL/Google thật. Maven package, kiểm tra responsive và script verify đều chạy lại thành công.

## Bằng chứng thực thi

| Kiểm tra | Kết quả | Phạm vi bằng chứng |
|---|---|---|
| `mvn.cmd -Dmaven.repo.local=C:\Users\DungLT\.m2\repository -o package -q` | Exit 0 | Java 21 / Boot 3.5.16; JAR đóng gói thành công |
| Surefire XML | 69 test: 68 đạt, 1 skipped; 0 failure/error | `verification-results.json`, `target/surefire-reports` |
| `python docs/registration/verify.py` | Exit 0 | Đếm test/schema thực tế, xác minh schema giữ nguyên, unique/FK/seed role, đối chiếu browser |
| `node docs/registration/browser-check.cjs` | Exit 0 | Edge headless, HTML render từ Thymeleaf/MockMvc |
| `git diff --check` | Exit 0 | Không có lỗi whitespace; cảnh báo LF/CRLF theo cấu hình Git |

CWD các lệnh: `D:/FPTU_document/SE_5/SWP391/Project/AureliaBooks_DDAGTech`.

## Checklist nghiệm thu và truy vết

| Yêu cầu | Kết quả trong phạm vi đã chạy | Bằng chứng |
|---|---|---|
| Local email/SĐT hợp lệ, duy nhất | PASS ở validation/service/MVC; SQL constraints hiện hữu | RegistrationServiceTest: invalid phone, duplicate email/phone; MVC: lỗi unique khi flush |
| SĐT 10 chữ số | PASS; theo bảng UI RDS phải bắt đầu bằng 0 | Request validation + parameterized phone tests |
| Mật khẩu >=8, chữ hoa/số/ký tự đặc biệt; xác nhận khớp | PASS; không bắt buộc chữ thường | Password boundary/invalid/mismatch tests; MVC không render lại mật khẩu |
| BCrypt, một CUSTOMER do server gán | PASS ở service; encoder production cost 12 | Hash verification, saved user role, MVC không có field role và bỏ qua role người dùng gửi |
| DOB optional, ngày quá khứ, không xét tuổi | PASS | null/1 ngày tuổi hợp lệ; hôm nay/tương lai/ngày không tồn tại bị từ chối |
| Thông báo tiếng Việt | PASS trong các luồng registration đã kiểm tra | Validation, duplicate, Google failure, CSRF; không đưa mã BR vào UI |
| CSRF trên POST | PASS | Token được Thymeleaf tự sinh; POST thiếu token 403 và không gọi service |
| Google verified email/sub/tên/avatar | PASS với claims giả lập | HTTP userinfo mock, OIDC extension test; principal chỉ có role DB |
| Google identity đã tồn tại không tạo thêm | PASS ở service | Existing active identity reused; existing inactive rejected |
| Email thuộc tài khoản khác không tự liên kết | PASS ở service | Email conflict rejected before save |
| Cấu hình Google thực tế được nối vào filter chain | PASS với client test | ActiveProfiles google đọc application-google.yaml; scopes/subject assertion; authorization redirect và callback hủy |
| Hủy Google không gây 500 | PASS | Filter dùng SessionFlashMapManager; lỗi hiện lại trên registration page |
| Responsive / màu index | PASS ở HTML render | 375/768/1440px không tràn ngang, không pageerror; nút rgb(217,74,38), ảnh đã xem ở mobile/desktop |
| Không đổi schema / phạm vi task | PASS đối chiếu file | 22 bảng, schema.sql không đổi so HEAD, khớp docker/initdb/01_schema.sql; chỉ registration, cấu hình Google, thông báo CSRF và test liên quan |

## Kiểm tra chưa chạy

- MySQL persistence, transaction rollback và hai request đăng ký đồng thời trên database thật: NOT_RUN. Docker CLI có nhưng Docker daemon báo không tìm thấy pipe; localhost:3306 chưa mở.
- Google Cloud với OAuth client và tài khoản thật, trao đổi token / xác minh JWT thực tế: NOT_RUN. Test dùng client/claims giả và cơ chế framework, không chứng minh một round trip Google thật.
- Test `contextLoads` sẵn có vẫn disabled do cần database. Không tính test này là đạt.
- Browser QA dùng HTML render bằng MockMvc; không phải test submit end-to-end trên ứng dụng đang chạy với database.

## Lưu ý bàn giao

Role phải được seed từ file hiện có; không tự tạo role hay thêm bảng. Nút Google chỉ bật khi profile google và credentials được cấu hình. Luồng Local thành công redirect tới trang Login hiện có; giao diện Login còn là scaffold và thuộc task riêng. Không triển khai cart, checkout, reset password, account linking hoặc chỉnh RDS trong task này.

RDS Member Registration Screen còn các thông tin cũ như minimum age/chữ thường/tạo cart; triển khai theo đầu việc được người dùng chốt. UI bám bảng field descriptions và layout của source; không tuyên bố khớp pixel với ảnh prototype.
