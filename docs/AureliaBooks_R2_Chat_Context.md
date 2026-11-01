# AureliaBooks — Cloudflare R2: báo cáo và context bàn giao

Cập nhật: **01/11/2026, giờ Việt Nam**. Báo cáo này thay thế trạng thái dừng R2.30.3. Nội dung lịch sử trước đó vẫn có trong Git và các bản sao báo cáo ngoài repo.

## Trạng thái hiện tại

**R2.30.4–R2.36 hoàn thành trong phạm vi code, mock/QA tests và thiết kế lifecycle. DỪNG trước R2.37.** Chưa có bằng chứng live Java R2 hoặc browser end-to-end; không coi toàn bộ tính năng đã sẵn sàng Production.

| Checkpoint | Trạng thái | Bằng chứng |
|---|---|---|
| R2.25–R2.27 | PASS trước phiên này | QA/schema và MySQL visibility tests; chạy lại 5 tests ở R2.36 |
| R2.28–R2.29 | PASS trước phiên này | Architecture review, GET controller và 6 HTTP tests |
| R2.30.1–R2.30.3 | PASS trước phiên này | Review, conditional context, 11 MVC security tests |
| R2.30.4 | PASS / CLOSED | 50 tests regression, 0 failure/error/skip |
| R2.31 | PASS — code/mock scope | POST MANAGER upload, JSON internal URL, local errors |
| R2.32 | PASS | 14 upload MVC tests: RBAC, CSRF, file processing, storage failure, multipart resolver errors |
| R2.33 | PASS — MVC render + JS mocks | Hai form dùng shared fragment, local preview, upload và giữ URL cũ khi lỗi |
| R2.34 | PASS | Policy URL trên cả 4 create/update paths; 20 policy tests, 4 save guards, form binding và regression |
| R2.35 | DONE — thiết kế lifecycle | Giữ orphan private, không auto-delete; quy trình review/cleanup trong docs/storage/R2_PRODUCT_IMAGES.md |
| R2.36 | PASS — selected regression | 179 Java tests + 5 MySQL QA tests + 8 JavaScript tests (ba lượt riêng), tài liệu bàn giao |
| R2.37 | CHƯA CHẠY — cần phê duyệt riêng | Live R2, PR và chuẩn bị Staging |

## Repository và Git

- Repo: `DungTLab/AureliaBooks_DDAGTech`.
- Worktree duy nhất được sửa: `/home/dungtlab/WorkSpace/AureliaBooks-r2-storage`.
- Branch: `feature/cloudflare-r2-storage`, base lúc khởi tạo `8b8051b`.
- Các commits trước phiên này đã push: `4537867` (storage/QA), `56cc060` (GET), `705d74f` (context), `4b89b27` (security MVC).
- Người dùng đã yêu cầu commit/push và sau đó cho triển khai liên tục phần code; dừng trước dịch vụ thật/deploy. Báo cáo trong repo được commit cùng code phiên này; xem Git log để lấy SHA mới nhất. Bản báo cáo ngoài repo bổ sung SHA sau push.
- Không sửa/reset worktree gốc branch ci/production-promotion hoặc worktree seeding.

## Kiến trúc cuối cùng

### Cấu hình và storage

Java 21, Spring Boot 3.5.16, AWS SDK v2 2.57.8. `app.r2.enabled=${R2_ENABLED:false}`; endpoint/bucket/access-key-id/secret-access-key lấy environment R2_ENDPOINT/R2_BUCKET/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY. Không chứa credentials thật trong source/report.

R2StorageConfig chỉ tạo S3Client khi bật; kiểm tra HTTPS endpoint Cloudflare, static credentials, region auto, path style bật, chunked encoding tắt; đóng client theo bean lifecycle. ProductImageStorage chỉ hoạt động khi bật.

Processor nhận PNG/JPEG theo nội dung decode thật, không tin filename/MIME; giới hạn input/output 5 MiB, 16 triệu pixel, resize tối đa 1600 px, encode lại PNG. Storage tạo key `products/<uuid-v4>.png`, không trả public URL. Read chỉ nhận UUID v4 lowercase, kiểm tra metadata/size, đọc có giới hạn, abort stream khi lỗi. Avatar giữ nguyên local storage và URL cũ.

### GET ảnh

`GET /products/images/{imageId}` chỉ đăng ký khi R2 bật. Validate UUID trước DB; `countVisibleProductsByMainImageUrl()` kiểm tra URL chính xác, product active và category active trước read storage. Count 0 → 404, object thiếu → 404, DB/storage lỗi → 503; success → PNG 200. Headers no-store/nosniff, lỗi body rỗng, local exception handlers tránh redirect HTML. Query không kiểm tra stock hoặc toàn bộ cây parent category.

Storefront chain thật permitAll `/products/**`; internal chain không match ảnh. Context và security MVC tests kiểm tra đúng thứ tự hai chains, GET anonymous, redirect portal và CSRF.

### POST MANAGER upload — mới

`POST /manager/products/images`, multipart part `file`, chỉ MANAGER, CSRF giữ nguyên. Không cho ADMIN/SALE_STAFF/CUSTOMER upload. Uploader chỉ đăng ký khi R2 bật.

201 JSON `{ "imageUrl": "/products/images/<uuid-v4>" }`, không có object key/endpoint/credentials. 400 cho file/input/missing part; 413 cho servlet size exception; 400 cho multipart parse lỗi; 503 khi R2 upload lỗi, không lộ cause qua HTTP body. GlobalExceptionHandler chỉ có nhánh multipart JSON riêng cho đúng API này, các route khác giữ xử lý cũ. Tests mock MultipartResolver để kiểm tra lỗi trước khi dispatch controller; không chứng minh servlet/proxy limits thật.

Không ghi DB trong upload. S3 PUT thành công chưa đồng nghĩa product được lưu; GET orphan vẫn 404. Upload MVC tests dùng processor và R2ProductImageStorage thật với S3Client mock; không gọi Cloudflare.

### Forms và lưu product — mới

Book/Stationery dùng chung `admin/products/image-field.html`: input type text để hỗ trợ internal URL lẫn HTTP/HTTPS. Khi R2 tắt: chỉ còn URL thủ công. Khi bật: chọn PNG/JPEG, local blob preview, upload AJAX kèm CSRF header và cookie same-origin; browser tự tạo multipart boundary.

JS không submit form trong lúc upload, chống click trùng trong cùng panel, không tự retry; giữ URL cũ khi lỗi và cập nhật field chỉ sau response internal URL hợp lệ. URL đã upload giữ trong form khi server validation lỗi. Blob preview được revoke khi đổi ảnh/URL hoặc rời trang. Reload không giữ blob preview; thông báo cho người dùng lưu product trước khi ảnh public.

BookForm/StationeryForm strip URL. ProductImageReferencePolicy chạy trước mutation/save ở createBook/updateBook/createStationery/updateStationery:

- Internal URL mới phải đúng UUID v4 lowercase, không query/fragment/traversal, và đọc được object trước ghi DB.
- Không có storage khi R2 tắt hoặc storage lỗi → không gắn internal URL mới. Object không tồn tại → báo input lỗi.
- External HTTP/HTTPS URL mới phải có hostname, không user-info/control/backslash; không nhận Cloudflare S3 API endpoint. Backend không fetch URL ngoài.
- Giữ nguyên URL không đổi, kể cả legacy path, không gọi R2; không rewrite dữ liệu cũ. Cho phép blank để bỏ ảnh.
- Manager có thể dùng lại internal URL tồn tại; không có ownership riêng theo người upload.
- Không đổi ProductMapper/entity/schema. Mapper tiếp tục lưu form URL đã chuẩn hóa sau khi service policy cho phép. Giữ nguyên stock invariants.

### Lifecycle — quyết định R2.35

Không thêm delete API, không auto-GC, không scheduled cleanup, không bucket expiration. Upload bỏ dở/save fail tạo object private; thay/bỏ ảnh chỉ thay DB URL, ảnh cũ được giữ. Shared references và sản phẩm inactive được bảo toàn. R2.35 là **thiết kế vận hành**, không phải feature automatic orphan cleanup.

Đánh đổi: orphan dùng dung lượng/cost cho tới khi đối soát có review. Muốn xóa: inventory dry-run đúng bucket/prefix, đối chiếu mọi product reference kể cả inactive, grace period, sau đó cửa sổ ngừng upload/save để loại race và xác minh lại; approval trên danh sách cụ thể, backup/audit rồi mới operator delete. Không dùng visibility count để quyết định xóa. Chi tiết: `docs/storage/R2_PRODUCT_IMAGES.md`.

## Kết quả kiểm thử phiên này

| Lượt | Kết quả | Thời điểm +07 |
|---|---|---|
| R2.30.4 regression ban đầu | 50 tests, 0 failures/errors/skipped | 19:18:38 |
| Upload/policy/admin/form validation đầu | 67 tests PASS | 19:22:16 |
| Form tests sau sửa assertions HTML | 13 tests PASS | 19:24:52 |
| R2.36 regression cuối | **179 tests, 0 failures/errors/skipped** | 19:27:14 |
| R2.36 MySQL QA query | **5 tests, 0 failures/errors/skipped** | 19:27:45 |
| JavaScript upload source cuối | **8 tests PASS** | Cùng phiên |

Lượt form ban đầu: 5 errors do dùng XML XPath để đọc HTML layout có raw ampersand. Thay bằng assertions đọc HTML phù hợp; không thay production template để né parser. Regression cuối PASS. Cảnh báo Mockito dynamic agent/deprecation cũ còn tồn tại. Maven được chạy ngoài sandbox để agent hoạt động.

Lệnh regression cuối (chỉ chọn classes sau, không full suite):

```bash
./mvnw -B -ntp -Dtest=ProductImageControllerTest,ProductImageControllerContextTest,ProductImageSecurityMvcTest,R2StorageConfigTest,ProductImageProcessorTest,R2ProductImageStorageTest,R2ProductImageReadTest,R2ProductImageAbortTest,ManagerProductImageUploadTest,ProductImageReferencePolicyTest,ProductImageSaveGuardTest,ProductImageFormTest,AdminProductServiceTest,AdminProductControllerTest,BookFormValidationTest,SecurityConfigIntegrationTest,PortalSeparationIntegrationTest test
bash scripts/test-r2-mysql.sh
```

JavaScript tests nằm trong `scripts/tests/product-image-upload.test.mjs`, chạy qua Node tool runtime; CLI node trên máy hiện chưa có. DOM/fetch được fake để kiểm tra success/CSRF/errors/URL preservation/submit block/blob cleanup; chưa phải browser E2E. Xem lệnh Node ESM mẫu trong tài liệu storage.

Không cộng các lượt lặp thành tổng tests duy nhất; 179 Java mock/MVC tests, 5 MySQL tests và 8 JS tests là các lượt độc lập. Chưa chạy full suite hoặc full SpringBootTest với datasource thật.

## QA và bảo mật

Container QA: `aureliabooks-r2-mysql-qa`, MySQL 8.4.13, database `r2_image_qa`, user `r2_qa`. Lúc tạo: 127.0.0.1:32768 → 3306; script lấy port động mỗi lần, không hardcode. Canonical QA schema đã import ở R2.26 bằng bản tạm bỏ DROP/CREATE DATABASE/USE; source gốc không đổi. Đã xác minh trước đó 21 bảng, 1 view, 28 FK. Phiên này không import hoặc đổi schema.

Credentials QA: `~/.local/state/aureliabooks-r2-qa/mysql.env` (ngoài Git, 0600; directory 0700). Script đọc đúng QA credentials mà không log/shell-evaluate file. Container `--rm`, không volume persistent; không tự xóa/tạo lại khi lỗi. MySQL tests ddl-auto=validate, SQL guard đúng database/user, transaction rollback. Chưa cleanup vì còn có thể dùng QA.

Bucket staging theo context lịch sử: `aureliabooks-staging-assets`, private. Tài liệu cũ ghi CLI smoke đã làm; phiên này không xác minh lại bucket/token hoặc live Java SDK. Không in credentials, không dump env/docker inspect toàn bộ.

**application.yaml vẫn có ddl-auto=update từ trước; không sửa trong phạm vi này và không khởi động ứng dụng với datasource thật.** Nhóm cần quyết định cấu hình validate trước live/staging. Schema/entity/migration không đổi; avatar không đổi; không tác động VPS, Staging/Production.

## Điểm dừng bắt buộc — R2.37 chưa làm

Chưa live R2 smoke, chưa PR, merge, build/deploy Staging/Production. Trước bước đó cần phê duyệt và chuẩn bị:

1. Môi trường/token/bucket cụ thể, secrets ngoài Git; xác minh private bucket và datasource/schema mode trước khởi động thật.
2. Live Java PUT/read với key QA và cleanup riêng được duyệt; không quét/xóa objects thật.
3. Browser E2E hai form, session/CSRF expiry, save failure/retry, thay ảnh/shared references, visibility, prefix/proxy paths và giới hạn multipart servlet thật.
4. Quota và rate limiting upload tại backend/gateway: bản này chưa có server rate limiter; client single-flight không thay thế cơ chế đó.
5. Đối soát chi phí orphan, chính sách cleanup được nhóm duyệt. Không tự triển khai delete/GC dựa trên context này.
6. Review PR/CI và rollback plan: tắt R2 làm internal GET không đăng ký, URL ngoài vẫn giữ; cần cân nhắc ảnh đã lưu internal khi rollout/rollback.

Hướng tiếp tục: đọc source và tài liệu storage, review diff đã commit, rồi chuẩn bị kế hoạch R2.37 cụ thể để người dùng duyệt. Không tự kết nối dịch vụ thật hoặc deploy.
