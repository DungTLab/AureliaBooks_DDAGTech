# AureliaBooks — Báo cáo và context Cloudflare R2

Cập nhật: 01/11/2026, giờ Việt Nam. Báo cáo thay thế trạng thái dừng R2.24 của hai tài liệu bàn giao trước. Đây là tài liệu bối cảnh; các bước dự kiến không tự cấp quyền triển khai lên môi trường thật.

## Trạng thái

**R2.25–R2.29 PASS / CLOSED. Tính năng upload ảnh sản phẩm chưa hoàn tất end-to-end.**

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
- Avatar giữ nguyên; URL ngoài hiện tại giữ nguyên. Đã bổ sung controller điều phối visibility trước storage ở R2.29 (xem phần cập nhật dưới).

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

1. Kiểm thử điều kiện đăng ký ProductImageController khi R2 bật/tắt trong application context và tương thích Spring Security thật; GET controller và HTTP standalone tests đã hoàn tất ở R2.29.
2. POST manager upload: role manager, CSRF, multipart validation, xử lý lỗi; trả URL nội bộ thống nhất. Không coi UUID là cơ chế phân quyền.
3. Tích hợp form sách/văn phòng phẩm, giữ URL ngoài và URL cũ; điều chỉnh input/validation để hỗ trợ đường dẫn nội bộ.
4. Xác định vòng đời object orphan khi upload/lưu DB thất bại hoặc thay ảnh; không tự xóa ảnh còn được tham chiếu.
5. Controller/security tests và R2 live integration trước khi coi tính năng hoàn thành; review rồi mới xem xét staging deployment.

Các checkpoint R2.09–R2.20 trong tài liệu cũ không có lịch sử gốc đầy đủ ở tài liệu thứ hai. Kết quả chạy lại 30 unit tests ở phiên này xác minh hành vi hiện tại, không phục hồi ánh xạ checkpoint lịch sử.

## Cập nhật R2.28–R2.29 — 01/11/2026

### R2.28 Architecture Review — PASS / CLOSED

Đối chiếu controller hiện có: chưa có `/products/images/{imageId}`, không xung đột với `/products/{id}`; repository đã có visibility query. Không cần sửa database schema.

### R2.29 GET Product Image Controller — PASS / CLOSED

Thêm `ProductImageController` chỉ đăng ký khi `app.r2.enabled=true`; cấu hình mặc định vẫn false.

- GET `/products/images/{imageId}` chỉ chấp nhận UUID v4 lowercase chuẩn, sai ID trả 404 trước database.
- Query theo đúng URL nội bộ; count <= 0 trả 404 trước storage, tránh phục vụ ảnh orphan.
- Count > 0 mới đọc storage; trả body PNG với Content-Type image/png.
- AppException RESOURCE_NOT_FOUND trả 404; lỗi database/R2 và các exception khác trả 503, không lộ exception qua body.
- Local exception handlers tránh GlobalExceptionHandler redirect HTML.
- Success/error đều có Cache-Control no-store và X-Content-Type-Options nosniff.

`ProductImageControllerTest`: 6/6 PASS, không failure/error/skip. MockMvc standalone có đăng ký GlobalExceptionHandler hiện có, xác minh local handlers ưu tiên khi có advice toàn cục. Các trường hợp: PNG thành công, UUID sai không truy cập DB/storage, ảnh chưa gắn không đọc storage, object thiếu 404, R2 lỗi 503, database lỗi 503 không đọc storage. Error responses có body rỗng, không Location, no-store, nosniff.

Chạy regression chọn lọc:

```bash
./mvnw -B -ntp -Dtest=ProductImageControllerTest,R2StorageConfigTest,ProductImageProcessorTest,R2ProductImageStorageTest,R2ProductImageReadTest,R2ProductImageAbortTest test
```

Kết quả: **36 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**, hoàn tất 19:00:03 +07 ngày 01/11/2026. Lần chạy đầu riêng HTTP tests trong sandbox lỗi Mockito attach (6 errors); lần chạy lại regression ngoài sandbox PASS. Không thay đổi cấu hình Maven để che lỗi.

MySQL visibility tests (5 tests) đã PASS ở phiên trước, không chạy lại vì R2.29 không sửa query/schema. Không cộng kết quả các phiên thành full suite mới. Không chạy MySQL/Cloudflare thật ở checkpoint này.

### Giới hạn và checkpoint tiếp theo

Standalone tests không chứng minh Spring Security hay conditional bean registration trong application context thật. Chưa kiểm thử hai nội dung đó cho controller mới; đây là bước kế tiếp, dự kiến R2.30. Chưa triển khai POST upload, form admin hoặc vòng đời object orphan. Không deploy, merge, thay đổi Staging/Production.

## R2.30.2 — Conditional Controller Context Tests: PASS

Cập nhật 01/11/2026: R2.30.1 đã đối chiếu hai SecurityFilterChain và mẫu WebMvcTest hiện có. R2.30.2 đã hoàn thành; **toàn bộ R2.30 chưa CLOSED**.

Thêm `ProductImageControllerContextTest`, dùng WebApplicationContextRunner với WebMvcAutoConfiguration và ProductImageController thật. Không scan toàn bộ ứng dụng, không khởi tạo datasource, JPA hay S3 client; không dùng MySQL QA hoặc credentials.

| Trường hợp | Bằng chứng |
|---|---|
| Không có app.r2.enabled | Context khởi tạo thành công, không controller/route ảnh, không cần storage/repository beans |
| app.r2.enabled=false | Context khởi tạo thành công, không controller/route ảnh, không cần storage/repository beans |
| app.r2.enabled=true | Có đúng một controller và mapping /products/images/{imageId}; repository/storage dùng mock, không bị gọi lúc khởi tạo |

Các tests kiểm tra property `app.r2.enabled`, không trực tiếp thay environment variable R2_ENABLED của máy. Cấu hình application.yaml hiện ánh xạ `${R2_ENABLED:false}` tới property này; runner cố ý không load application.yaml để cách ly datasource ứng dụng. Chưa chứng minh toàn bộ Boot application hoặc environment binding end-to-end.

Lệnh:

```bash
./mvnw -B -ntp -Dtest=ProductImageControllerContextTest test
```

Kết quả **3 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS** lúc 19:07:09 +07. Lần đầu trong sandbox gặp lỗi Mockito attach; chạy lại ngoài sandbox PASS. Không sửa production code, security config, schema hoặc Maven.

Bước đang chờ: **R2.30.3** — WebMvcTest với SecurityConfig thật, không tắt filters; anonymous GET, các status 200/404/503 và headers. Sau đó R2.30.4 chạy regression và tổng kết. Kết quả R2.30.2 không phải bằng chứng Spring Security đã PASS. Chưa chuyển R2.31.

## R2.30.3 — MVC Security Tests: PASS

Ngày 01/11/2026, thêm `ProductImageSecurityMvcTest`. Dùng `@WebMvcTest(ProductImageController.class, properties="app.r2.enabled=true")`, import SecurityConfig, CustomAuthenticationEntryPoint và CustomAccessDeniedHandler thật. Không định nghĩa filter chain giả, không tắt filters. Repository/storage là hai mocks; không khởi tạo datasource/JPA hoặc S3 client, không chạy MySQL QA, không đọc credentials hay gọi R2/VPS.

| Kiểm thử | Kết quả |
|---|---|
| Hai chains thật đúng thứ tự internal → storefront | PASS |
| Chọn chain đầu tiên cho ảnh, /profile, /manager/books | PASS |
| Internal chain không match ảnh; cả hai chains có CsrfFilter | PASS |
| Anonymous GET ảnh hợp lệ, không cần CSRF | 200 image/png, đúng bytes |
| UUID sai | 404; không gọi repository/storage |
| Orphan | 404; không gọi storage |
| Object thiếu | 404 |
| Storage lỗi | 503 |
| Database lỗi | 503; không gọi storage |
| Anonymous /manager/books | 302 → /internal/login |
| Anonymous /profile | 302 → /auth/login |
| POST /manager/products/images thiếu CSRF (MANAGER) | 403, forward /error/403, không redirect |
| POST /products/images thiếu CSRF (MANAGER) | 403, forward /error/403, không redirect |

Các response GET ảnh 200/404/503 đều được kiểm tra Cache-Control no-store, X-Content-Type-Options nosniff và không có Location; response lỗi body rỗng. Test redirect/CSRF xác minh mocks không được gọi. Test chain kiểm tra chain đầu tiên được chọn, không giả định storefront catch-all phải từ chối match internal URI.

Output R2.30.3:

```text
./mvnw -B -ntp -Dtest=ProductImageSecurityMvcTest test
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-11-01T19:13:12+07:00
```

Chạy ngoài sandbox để Mockito agent hoạt động. Cảnh báo Mockito self-attach còn tồn tại; stack traces từ lỗi storage/database được mock là tình huống test dự kiến, không phải test failure.

Giới hạn: đây là MVC security slice, không phải full application end-to-end. Các trang manager/profile không được load controller trang thật; tests xác minh redirect của security trước MVC. POST endpoint ảnh chưa tồn tại; test CSRF chỉ chứng minh filter từ chối request trước MVC, chưa chứng minh upload hay phân quyền upload khi có CSRF hợp lệ.

**R2.30.3 PASS; toàn bộ R2.30 vẫn chưa CLOSED.** Bước kế tiếp là R2.30.4 regression chọn lọc và tổng kết; chưa chạy bước này, chưa làm R2.31. Không sửa production code/security config/schema và không deploy.
