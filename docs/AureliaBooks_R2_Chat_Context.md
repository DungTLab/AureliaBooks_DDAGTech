# AureliaBooks — Cloudflare R2: báo cáo và context bàn giao

Cập nhật: **01/11/2026, giờ Việt Nam**. Báo cáo này bao gồm cập nhật R2.37.1 sau phần code R2.36. Nội dung lịch sử trước đó vẫn có trong Git và các bản sao báo cáo ngoài repo.

## Trạng thái hiện tại

**R2.36 hoàn thành trong phạm vi đã báo cáo; R2.37.1 PASS source audit/hardening. R2.37.2 PASS / CLOSED trong phạm vi QA MySQL + local Nginx + fake storage; R2.37.3–R2.37.5 chưa chạy.** Chưa có bằng chứng live Java R2 hoặc browser end-to-end; không coi toàn bộ tính năng đã sẵn sàng Production.

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
| R2.37.1 | PASS — offline/source/QA scope | Default validate, admission limits, upload pause và audit rollback |
| R2.37.2 | PASS / CLOSED — QA scope | Real MySQL/JPA/services, browser, Nginx; 28 HTTP E2E cases và cleanup; storage/accounts synthetic |
| R2.37.3–R2.37.5 | CHƯA CHẠY | Chưa live R2, PR/CI review, merge hoặc deploy |

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

R2StorageConfig chỉ tạo S3Client khi bật; kiểm tra HTTPS endpoint Cloudflare, static credentials, region auto, path style bật, chunked encoding tắt; đóng client theo bean lifecycle. ProductImageStorage chỉ hoạt động khi bật. SDK call timeout 30s, attempt 10s sau R2.37.1.

Processor nhận PNG/JPEG theo nội dung decode thật, không tin filename/MIME; giới hạn input/output 5 MiB, 16 triệu pixel, resize tối đa 1600 px, encode lại PNG. Storage tạo key `products/<uuid-v4>.png`, không trả public URL. Read chỉ nhận UUID v4 lowercase, kiểm tra metadata/size, đọc có giới hạn, abort stream khi lỗi. Avatar giữ nguyên local storage và URL cũ.

### GET ảnh

`GET /products/images/{imageId}` chỉ đăng ký khi R2 bật. Validate UUID trước DB; `countVisibleProductsByMainImageUrl()` kiểm tra URL chính xác, product active và category active trước read storage. Count 0 → 404, object thiếu → 404, DB/storage lỗi → 503; success → PNG 200. Headers no-store/nosniff, lỗi body rỗng, local exception handlers tránh redirect HTML. Query không kiểm tra stock hoặc toàn bộ cây parent category.

Storefront chain thật permitAll `/products/**`; internal chain không match ảnh. Context và security MVC tests kiểm tra đúng thứ tự hai chains, GET anonymous, redirect portal và CSRF.

### POST MANAGER upload — mới

`POST /manager/products/images`, multipart part `file`, chỉ MANAGER, CSRF giữ nguyên. Không cho ADMIN/SALE_STAFF/CUSTOMER upload. Uploader chỉ đăng ký khi R2 bật và app.r2.upload-enabled=true (mặc định true); pause upload không gỡ GET controller.

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

**R2.37.1 đã đổi mặc định application.yaml sang ddl-auto=validate và bỏ JDBC createDatabaseIfNotExist. Không khởi động production application với datasource thật. External env/profile có thể override default, phải audit môi trường thật trước live/deploy.** Nhóm cần quyết định cấu hình validate trước live/staging. Schema/entity/migration không đổi; avatar không đổi; không tác động VPS, Staging/Production.

## Điểm dừng bắt buộc — live/PR/deploy chưa làm

Chưa live R2 smoke, chưa PR, merge, build/deploy Staging/Production. Trước bước đó cần phê duyệt và chuẩn bị:

1. Môi trường/token/bucket staging cụ thể được phê duyệt, secrets ngoài Git; xác minh private bucket và datasource/schema mode trước khởi động thật.
2. Live Java PUT/read với key QA và cleanup riêng được duyệt; không quét/xóa objects thật.
3. Browser E2E hai form, session/CSRF expiry, save failure/retry, thay ảnh/shared references, visibility, prefix/proxy paths và giới hạn multipart servlet thật.
4. Quota và rate limiting upload tại backend/gateway: R2.37.1 đã thêm backend quota/concurrency per JVM; cần ingress/shared limits khi nhiều instance, không dùng client single-flight thay thế.
5. Đối soát chi phí orphan, chính sách cleanup được nhóm duyệt. Không tự triển khai delete/GC dựa trên context này.
6. Review PR/CI và rollback plan: tắt R2 làm internal GET không đăng ký, URL ngoài vẫn giữ; cần cân nhắc ảnh đã lưu internal khi rollout/rollback.

Hướng tiếp tục: R2.37.2 đã đóng trong QA scope (xem cập nhật cuối). Đọc R2_RELEASE_READINESS.md rồi chuẩn bị live smoke R2.37.3 cụ thể để người dùng duyệt; chưa tự gọi R2 thật. Không tự kết nối dịch vụ thật hoặc deploy.

## Cập nhật R2.37.1 — 01/11/2026

Đã hoàn thành source audit/hardening; báo cáo chi tiết: `docs/storage/R2_RELEASE_READINESS.md`.

### Thay đổi

- Default DDL validate, bỏ driver auto-create database. Không đổi schema/entities/migrations hoặc chạy DB thật.
- File 5 MiB, request 6 MiB, disk threshold 0; header Content-Length quá lớn bị chặn sớm, chunked vẫn chịu servlet limits.
- ProductImageUploadFilter trong internal chain trước CSRF: 10 attempts/phút/principal MANAGER, tối đa 2 concurrent POST uploads/JVM. Quota map tối đa 1024, prune expired, fail-closed khi đầy; không tin forwarded-IP header. 429 JSON và Retry-After, release slot cả khi lỗi.
- Các giới hạn phải positive; cấu hình sai fail startup. Counter là per-process fixed window, reset khi restart, không global/distributed; cần edge limits để bảo vệ slow clients/multi-replica.
- R2_UPLOAD_ENABLED=false giữ R2 GET/storage, không đăng ký POST controller, ẩn uploader. POST bị filter trả 503 trước parse. Đây là pause upload, không rollback binary/database và không freeze mọi product write.
- S3 apiCallTimeout 30s, apiCallAttemptTimeout 10s. JS hiển thị thông báo 429, giữ URL, không auto retry.
- .env.example bổ sung tên biến (secret rỗng). Compose .env không tự forward các biến vào app container; deployment cụ thể chưa kiểm chứng/chưa sửa.

### Bằng chứng mới

**192 Java tests, 0 failures/errors/skipped, BUILD SUCCESS**, kết thúc 19:45:27 +07; **9 JS tests PASS**. Không chạy lại MySQL QA trong R2.37.1; 5 MySQL PASS ở R2.36 là lịch sử riêng.

```bash
./mvnw -B -ntp -Dtest=ProductImageControllerTest,ProductImageControllerContextTest,ProductImageSecurityMvcTest,R2StorageConfigTest,R2SafetyDefaultsTest,ProductImageProcessorTest,R2ProductImageStorageTest,R2ProductImageReadTest,R2ProductImageAbortTest,ManagerProductImageUploadTest,ProductImageReferencePolicyTest,ProductImageSaveGuardTest,ProductImageFormTest,AdminProductServiceTest,AdminProductControllerTest,BookFormValidationTest,SecurityConfigIntegrationTest,PortalSeparationIntegrationTest,ProductImageUploadFilterTest,ProductImageUploadPausedMvcTest,ProductImageUploadAdmissionMvcTest test
```

Đã xây `R2BrowserQaTest` + `scripts/start-r2-browser-qa.sh`: loopback/random port, không production root component scan, loại datasource/JPA, assert không có production root/DataSource/S3Client beans. Login synthetic; catalog/storage trong RAM; actual controllers/Security/Thymeleaf/embedded Tomcat. Tự dừng tối đa 15 phút hoặc stop file. Harness đã dừng sạch 19:44:09 +07. JUnit harness 1 PASS chỉ xác nhận safety/lifecycle, không là automated browser test count.

Browser thật đã kiểm chứng: login, upload Book/Stationery, local preview, fake PNG và oversized file bị từ chối giữ URL cũ, lưu form và đọc lại edit URL; orphan trước save 404, sau save anonymous GET 200 PNG/no-store/nosniff. Actual HTTP chunked >6 MiB tới QA servlet trả 413 JSON không redirect. Fault memory storage trả 503 trên browser, giữ URL cũ. Screenshot versioned: `docs/storage/qa-evidence/r2371-upload-error.jpg`.

Đây là smoke trên memory doubles, không chứng minh transaction/business persistence/MySQL/R2 thật, actual proxy hay toàn bộ global layout advice. Vì vậy **R2.37.2 vẫn PARTIAL**. Lượt harness bootstrap đầu lỗi import trùng User và sau đó phát hiện Spring ghép application root; đã sửa thành explicit QA config/TestComponent trước phiên browser thành công, datasource/JPA bị exclude trong mọi lượt.

### Kết luận rollback và điểm dừng

Sau khi DB có internal URLs, **không tắt R2_ENABLED như một rollback an toàn**. Pause upload bằng R2_UPLOAD_ENABLED=false giữ GET nếu DB/R2 healthy. Rollback binary phải giữ GET path/visibility hoặc có kế hoạch chuyển URL/restore mapping được nhóm duyệt; không auto-delete object. Không thể khôi phục URL cũ nếu chưa có inventory/audit trước thay ảnh.

Mẫu proxy, static audit và runbook có trong R2_RELEASE_READINESS.md; chưa áp dụng/kiểm chứng ở VPS. Default validate chưa chứng minh actual env không override. R2.37.2 cần tiếp tục persistence/proxy/session-expiry matrix; chỉ sau các điều kiện phù hợp mới xin phê duyệt R2.37.3 bucket staging. Chưa live smoke, PR, merge hay deploy.

## R2.37.2 — Browser End-to-End Verification: PASS / CLOSED (QA scope)

Cập nhật 01/11/2026. Không dùng database ứng dụng, bucket R2, VPS, PR/merge/deploy. Chi tiết và evidence: `docs/storage/R2_RELEASE_READINESS.md`, `docs/storage/qa-evidence/r2372/`.

### Môi trường và thành phần thật

- `R2PersistenceBrowserQaTest`: explicit QA composition, không production component scan; assert không có production root hoặc S3Client. Guard JDBC localhost + port lấy từ đúng container, cố định database r2_image_qa/user r2_qa; SELECT DATABASE()/CURRENT_USER() trước bootstrap. Credentials ngoài Git, không in ra.
- MySQL 8.4.13 thật, canonical schema giữ nguyên, ddl-auto=validate, SQL init never; JPA repositories, entities, transaction manager, AdminProductServiceImpl, MasterDataServiceImpl và mappers thật. Không mock catalog repository/service.
- Hai Spring contexts loopback/random ports, cùng QA DB và fake storage, một bật upload, một pause. Chỉ storage và UserDetailsService/identities synthetic; SecurityFilterChain/session/CSRF thực tế.
- Official Nginx 1.28-alpine pin digest, container tạm read-only/host networking chỉ listen loopback, context path /qa được giữ xuyên proxy. Không dùng cấu hình proxy đang chạy trên VPS.

### Các nhóm kiểm tra đã đạt

1. **Persistence:** upload PNG → tạo Book/Stationery → commit SQL, stock=0 → đọc lại form URL → anonymous GET 200 PNG. Browser thật lưu Book và Stationery, mở lại edit, thumbnail naturalWidth=160 qua /qa/products/images. Browser Stationery dùng lại ảnh vừa upload cho Book; HTTP suite kiểm tra riêng Stationery upload object mới.
2. **Auth/CSRF:** anonymous với token hợp lệ 401; ADMIN/SALE_STAFF/CUSTOMER với token hợp lệ 403; thiếu/sai token 403; session inactivity timeout 1s và CSRF cũ sau logout/login bị từ chối. Đây không phải production account-provider/database-login test.
3. **Recovery:** storage fail 503 giữ DB/object set; fault sau em.flush() trước commit ở cả bốn create/update paths → SQL rollback, form 503 giữ URL, không redirect. Retry lưu được. Orphan trước save/rollback 404 và không bị auto-delete; shared active references giữ GET 200.
4. **HTTP/proxy:** declared/chunked multipart quá 6 MiB bị Nginx 413; direct Tomcat chunked cũng 413 JSON; form action/img src giữ /qa, redirects không lộ backend origin. External HTTP URL/query string round-trip nguyên chuỗi.
5. **Admission/pause:** QA quota override 6/phút (production default10), request thứ7 429 Retry-After60; giữ2 actual upload requests, thứ3 429 Retry-After1, release vềinFlight0; upload-disabled không có uploader, POST503, GET ảnh đã lưu vẫn200.
6. **Evidence/cleanup:** reports HTTP/JUnit, SQL snapshots, screenshots, baseline/final counts đã version. Products về baseline0; không còn fixture master-data R2E2E_; 21tables/1view không đổi. Hai contexts và proxy QA đã dừng; container MySQL QA giữ nguyên.

### Lỗi E2E phát hiện và sửa

- Form actions mất context path → Thymeleaf link expressions cho Book/Stationery.
- Internal image src mất context path → sửa admin/storefront/cart/shared image templates, không rewrite DB.
- Lỗi DB/transaction save làm redirect mất form → 4 POST paths trả503 cùng form/URL. Nếu dropdown query cũng lỗi, render lists rỗng; không hứa local blob sống qua reload.
- Existing access-denied logging có thể chứa giá trị CSRF → chỉ log exception type. Test xác nhận không lộ token vào logs/body.

### Bằng chứng tests

- Lượt đầu: 25 HTTP cases, 22 PASS +3 lỗi thật ở context form/image và save error redirect.
- Sau sửa: **28 HTTP E2E cases PASS** qua Nginx + real MySQL; cả bốn transaction fault cases thực thi SQL flush trước rollback.
- **235 Java regression PASS**, 0 failures/errors/skips, 20:48:22 +07 (bao gồm home/product/cart và form recovery).
- Sau thay đổi logging: **75 focused security tests PASS**, 20:52:25 +07; overlap regression, không cộng thành310 independent tests.
- Persistence harness runner tự động: **JUnit1 PASS**, HTTP28 PASS và cleanup đúng baseline. Ngưỡng bắt buộc >=28 cases; cleanup kiểm tra category ID + run prefix trước DELETE, không truncate/drop DB.
- Không chạy full suite/GitHub CI, không chạy lại JS; 9 JS PASS ở R2.37.1 là lịch sử riêng.

Phiên manual browser bị tool delay khoảng26phút và chạm lease30phút trước stopmarker nên lifecycleJUnit của lượt đó FAIL do timeout (cleanup vẫn thành công); SQL/browser observations giữ riêng trong evidence. Runner tự động sau đó chạy lại toàn matrix, explicitstop đúng hạn và PASS lifecycle mà không nới assertion. Hai lỗi setup logging test (expected CSRF token null, ObjectMapper thiếu JavaTime module) đã sửa trước focused suite cuối PASS. Không che các lượt lỗi thành PASS.

### Chạy lại và phạm vi

```bash
python3 scripts/r2-e2e/run_checks.py
```

Cần container MySQL QA đúng tên + schema đã review, Java21, Python3, Docker. Script tự chạy/dừng server và proxy, kiểm tra HTTP report/cleanup. Xem `scripts/r2-e2e/README.md` để chạy browser thủ công; không dùng tài khoản thật hoặc đăng ký harness vào production artifact.

**R2.37.2 CLOSED trong phạm vi QA với storage/accounts synthetic.** Live SDK/bucket permissions còn thuộc R2.37.3; actual VPS/Tunnel/TLS thuộc release/deploy review. Không mô phỏng commit outcome không xác định do network partition sau commit. Bước kế tiếp chỉ là chuẩn bị phê duyệt live staging smoke; chưa gọi R2 thật, chưa PR/merge/deploy.
