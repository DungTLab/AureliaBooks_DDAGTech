# AureliaBooks — Báo cáo và context Cloudflare R2

Cập nhật: 01/11/2026, giờ Việt Nam. Báo cáo thay thế trạng thái dừng R2.24 của hai tài liệu bàn giao trước. Đây là tài liệu bối cảnh; các bước dự kiến không tự cấp quyền triển khai lên môi trường thật.

## Trạng thái

**R2.25 PASS; R2.26 PASS; R2.27 PASS. Tính năng upload ảnh sản phẩm chưa hoàn tất end-to-end.**

- Repo: `DungTLab/AureliaBooks_DDAGTech`.
- Worktree: `/home/dungtlab/WorkSpace/AureliaBooks-r2-storage`.
- Branch: `feature/cloudflare-r2-storage`, khởi đầu ở `8b8051b`.
- Người dùng đã yêu cầu tiếp tục công việc, commit và push. Báo cáo này được đưa vào cùng commit code; xem lịch sử Git để lấy mã commit và đối chiếu remote.
- Không sửa worktree gốc `AureliaBooks_DDAGTech` hay các worktree seeding.

## Công việc đã thực hiện trong phiên này

### R2.25 — Xác minh QA: PASS

Container `aureliabooks-r2-mysql-qa` đang hoạt động, MySQL `8.4.13`, host binding `127.0.0.1:32768` tới cổng 3306 của container. SQL đăng nhập bằng user QA trả database `r2_image_qa`, số bảng ban đầu 0. Port là giá trị của phiên này, không được hardcode cho lần chạy sau.

### R2.26 — Review và nạp canonical schema vào QA: PASS

Đã đọc toàn bộ `docker/initdb/01_schema.sql`. Header chứa DROP DATABASE / CREATE DATABASE / USE trỏ tới `aurelia_books_db`. Tạo bản tạm `/tmp/aureliabooks-r2-qa-schema.sql`, bỏ đúng ba câu lệnh đó, giữ nguyên schema gốc. Phần còn lại tạo tables, indexes và view, không có database switching/routines/executable comments cần thực thi. Import bằng user QA trong container, kiểm tra database/user và số bảng 0 trước import.

Kết quả SQL sau import và kiểm thử:

| Kiểm tra | Kết quả |
|---|---:|
| Base tables | 21 |
| Views | 1 |
| Foreign key constraints | 28 |
| Products sau rollback | 0 |
| Categories sau rollback | 0 |

Không thay đổi schema source, entity mapping hoặc database ứng dụng. Hibernate trong test dùng `ddl-auto=validate`.

### R2.27 — Visibility query MySQL: PASS

Thêm `ProductImageVisibilityMySqlTest` và `scripts/test-r2-mysql.sh`.

- Chỉ chạy khi opt-in `-Dr2.mysql=true`.
- Script lấy cổng từ đúng container QA, yêu cầu chỉ bind `127.0.0.1`, đọc password từ file ngoài Git mà không in hoặc shell-evaluate nội dung.
- Test yêu cầu cổng/password QA tường minh, cố định host `127.0.0.1`, database `r2_image_qa`, user `r2_qa`; xác minh `DATABASE()` và `CURRENT_USER()` trước khởi tạo Hibernate. Không fallback sang datasource ứng dụng.
- Guard không chứng minh danh tính container nếu tự chạy Maven với biến môi trường tùy ý; dùng script để lấy mapping hiện tại.
- Mỗi test chạy trong transaction rollback, flush/clear trước query thực.
- 5 trường hợp: product/category active → 1; product inactive → 0; category inactive → 0; URL không được tham chiếu → 0; hai product active và một inactive cùng URL → 2.

## Nền tảng R2 được đưa vào commit

Các thay đổi đã có từ phiên trước được review và kiểm thử lại:

- AWS SDK v2 BOM `2.57.8`, dependency S3 trong Maven.
- `app.r2.*` đọc environment, disabled mặc định; fail khi bật nhưng thiếu cấu hình.
- S3Client dùng HTTPS endpoint Cloudflare, static credentials, region auto, path style, tắt chunked encoding.
- `ProductImageProcessor`: PNG/JPEG, tối đa 5 MiB input/output, 16 triệu pixel, resize tối đa 1600 px, encode lại PNG.
- `ProductImageStorage.store()` trả key `products/<UUID-v4>.png`, không trả public URL.
- Read chỉ nhận UUID v4 lowercase; kiểm tra metadata/size, đọc tối đa 5 MiB + 1 byte, abort stream khi lỗi hoặc dữ liệu không hợp lệ; S3 404 thành RESOURCE_NOT_FOUND.
- `ProductRepository.countVisibleProductsByMainImageUrl()` chỉ đếm product active thuộc category active. Không kiểm tra toàn bộ cây category/stock.
- Avatar giữ nguyên; URL ngoài hiện tại giữ nguyên. Chưa có controller điều phối visibility trước storage.

## Bằng chứng kiểm thử

| Test class | Tests | Failures / Errors / Skipped |
|---|---:|---|
| R2StorageConfigTest | 5 | 0 / 0 / 0 |
| ProductImageProcessorTest | 7 | 0 / 0 / 0 |
| R2ProductImageStorageTest | 5 | 0 / 0 / 0 |
| R2ProductImageReadTest | 8 | 0 / 0 / 0 |
| R2ProductImageAbortTest | 5 | 0 / 0 / 0 |
| ProductImageVisibilityMySqlTest | 5 | 0 / 0 / 0 |
| Tổng các tests được chọn | 35 | 0 / 0 / 0 |

MySQL tests BUILD SUCCESS lúc 18:12:22 +07; unit tests BUILD SUCCESS lúc 18:12:56 +07 ngày 01/11/2026. Lần chạy unit ban đầu trong sandbox có 21 errors do Mockito không attach được agent; chạy lại ngoài sandbox PASS. Cảnh báo Mockito self-attach và deprecation từ tests có sẵn vẫn tồn tại. Chưa chạy full suite, R2 live Java integration hay end-to-end.

Chạy lại sau khi QA schema đã chuẩn bị:

```bash
cd /home/dungtlab/WorkSpace/AureliaBooks-r2-storage
bash scripts/test-r2-mysql.sh
./mvnw -B -ntp -Dtest=R2StorageConfigTest,ProductImageProcessorTest,R2ProductImageStorageTest,R2ProductImageReadTest,R2ProductImageAbortTest test
```

Không chạy full suite hoặc MySQL tests checkout/cart để thay thế các lệnh chọn lọc này.

## Môi trường và secrets

Credentials QA vẫn ở `~/.local/state/aureliabooks-r2-qa/mysql.env`, ngoài repo. Không in file, không commit secrets, không dump toàn bộ docker inspect/env. Container chạy với `--rm`, không gắn volume dữ liệu; nếu container bị xóa, phải chuẩn bị lại schema có review. Chưa cleanup vì còn dùng QA.

Tài liệu đầu tiên ghi nhận bucket private `aureliabooks-staging-assets` và AWS CLI put/get/hash/delete smoke test trước đây; tài liệu thứ hai thiếu lịch sử gốc để kiểm chứng phần này. Phiên này không kết nối Cloudflare thật, không thay quyền bucket hoặc credentials.

Database schema dự án vẫn freeze. Không thực hiện deploy, migration, merge, thao tác VPS, Staging/Production hoặc sửa avatar. Không có credentials thật trong báo cáo.

## Việc tiếp theo — chưa làm

1. Thiết kế và triển khai GET `/products/images/{imageId}`: UUID hợp lệ, product visibility trước đọc S3; HTTP 200/404/503 trực tiếp, tránh redirect HTML; cache và security headers cần kiểm thử.
2. POST manager upload: role manager, CSRF, multipart validation, xử lý lỗi; trả URL nội bộ thống nhất. Không coi UUID là cơ chế phân quyền.
3. Tích hợp form sách/văn phòng phẩm, giữ URL ngoài và URL cũ; điều chỉnh input/validation để hỗ trợ đường dẫn nội bộ.
4. Xác định vòng đời object orphan khi upload/lưu DB thất bại hoặc thay ảnh; không tự xóa ảnh còn được tham chiếu.
5. Controller/security tests và R2 live integration trước khi coi tính năng hoàn thành; review rồi mới xem xét staging deployment.

Các checkpoint R2.09–R2.20 trong tài liệu cũ không có lịch sử gốc đầy đủ ở tài liệu thứ hai. Kết quả chạy lại 30 unit tests ở phiên này xác minh hành vi hiện tại, không phục hồi ánh xạ checkpoint lịch sử.
