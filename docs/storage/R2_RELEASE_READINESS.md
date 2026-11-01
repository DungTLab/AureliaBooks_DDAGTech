# R2.37.1 — Static audit và hardening (01/11/2026)

## Kết luận

**PASS trong phạm vi source audit, hardening và kiểm thử QA/mocks. Chưa đủ điều kiện deploy.** Đã xây dựng browser QA harness và thử smoke trên browser thật; **R2.37.2 PARTIAL**, chưa phải full application/database/R2 E2E. R2.37.3–R2.37.5 chưa thực hiện; không kết nối bucket, mở PR, merge hoặc deploy.

## Phát hiện và thay đổi

| Hạng mục | Trước | Sau | Giới hạn bằng chứng |
|---|---|---|---|
| Hibernate DDL | update | validate mặc định | Không có datasource thật được khởi động; deployment overrides vẫn phải audit |
| JDBC tự tạo DB | createDatabaseIfNotExist=true | Bỏ tham số | Không thay schema hay dữ liệu |
| Multipart | file 5 MiB, request 10 MiB | file 5 MiB, request 6 MiB, disk threshold 0 | Request chunked thật >6 MiB trên QA Tomcat → 413 JSON |
| Upload rate | Chỉ client single-flight | 10 attempts / phút / principal MANAGER / JVM | Fixed window; invalid/failed attempts cũng tính quota |
| Concurrency | Chưa có | Tối đa 2 POST uploads đang xử lý / JVM, không chờ slot | Chạy trước CSRF/multipart parameter parsing; không phải edge DDoS protection |
| Map quota | Chưa có | Tối đa 1024 identities, prune expired, từ chối khi đầy | Không evict live quota để tránh bypass; restart mất quota |
| Backpressure | Chưa có | 429 JSON, Retry-After 60 (quota) / 1 (capacity) | Browser có thông báo 429, không tự retry |
| Pause upload | Chỉ tắt toàn R2 | R2_UPLOAD_ENABLED=false giữ GET/storage, tắt POST controller và ẩn uploader | Restart cần áp dụng env mới; POST bị admission filter trả 503 trước parse |
| SDK timeout | SDK defaults | API call 30s, attempt 10s | Unit tests đọc config thật; chưa đo live network |

`ProductImageUploadFilter` được tạo duy nhất trong internal SecurityFilterChain, trước CsrfFilter, không là servlet Filter bean để tránh chạy hai lần. Không thay role rules hoặc tắt CSRF. Admission không tự cấp quyền; auth/role/CSRF vẫn đi qua chain thật. Không đọc body/parameters trước admission. Anonymous/role khác không tạo quota identity; vẫn chịu concurrency và servlet limits. Không dùng IP hay X-Forwarded-* làm danh tính quota.

Config mới trong Spring process/container:

```text
R2_ENABLED=false                     # default; tắt cả GET/storage/upload
R2_UPLOAD_ENABLED=true               # default, chỉ có tác dụng upload khi R2_ENABLED=true
R2_UPLOAD_REQUESTS_PER_MINUTE=10
R2_UPLOAD_MAX_CONCURRENT=2
```

`R2_UPLOAD_ENABLED=false` không cấm lưu lại URL ảnh đã có hoặc gắn lại một object tồn tại. Đây là pause upload, không phải cửa sổ maintenance khóa toàn bộ product writes.

## Ma trận rollback

| Tình huống | Thao tác đề xuất | Tác động |
|---|---|---|
| Chưa có internal URLs trong DB | Có thể tắt R2 sau khi kiểm kê đúng môi trường | URL ngoài giữ nguyên |
| Đã có internal URLs, upload lỗi/chi phí tăng | Giữ R2_ENABLED=true, đặt R2_UPLOAD_ENABLED=false | Ẩn upload, giữ GET/storage và ảnh cũ nếu DB/R2 healthy |
| DB/R2 outage | Pause upload, phục hồi dịch vụ; GET có thể 503 | Pause không chữa outage và không khôi phục dữ liệu |
| Rollback binary sau khi đã lưu internal URLs | Chỉ dùng bản đã xác minh vẫn hỗ trợ GET path/visibility/schema hiện tại | Không quay về binary trước tính năng R2 mà không có phương án chuyển URL |
| Buộc tắt toàn bộ R2 hoặc quay về binary cũ | Cần inventory/export và bản đồ URL cũ đã duyệt, backup và kế hoạch chuyển dữ liệu riêng | Code này không tự khôi phục URL cũ; chưa thực thi bất kỳ rewrite nào |

**Tắt R2_ENABLED không phải rollback an toàn sau khi có dữ liệu thật.** Không auto-delete object khi rollback. Trước first rollout cần lưu inventory ID → URL và quyết định cách phục hồi cùng nhóm; không có lịch sử URL cũ bền vững trong schema hiện tại.

## Reverse proxy — chỉ là mẫu, chưa áp dụng/verify

Không có cấu hình reverse proxy staging hiện hành trong repo được kiểm chứng. Compose app hiện chỉ forward DB/PORT; đặt R2 vars trong file .env thôi chưa bảo đảm chúng vào Spring container. Không sửa compose/deploy scripts/VPS trong checkpoint này.

Ví dụ Nginx sau cần được ghép vào cấu hình hiện hữu, review upstream/auth/real-IP và `nginx -t` ở giai đoạn deploy riêng. Không coi đây là cấu hình đang chạy:

```nginx
# http context: shared per-IP zones, không phải quota theo account.
limit_req_zone $binary_remote_addr zone=r2_upload_rate:10m rate=10r/m;
limit_conn_zone $binary_remote_addr zone=r2_upload_conn:10m;

# server context: giữ các auth/Zero Trust/proxy headers hiện có.
location = /manager/products/images {
    client_max_body_size 6m;
    client_body_timeout 15s;
    limit_req zone=r2_upload_rate burst=2 nodelay;
    limit_req_status 429;
    limit_conn r2_upload_conn 2;
    limit_conn_status 429;
    proxy_connect_timeout 5s;
    proxy_send_timeout 15s;
    proxy_read_timeout 40s;
    proxy_pass http://aureliabooks_backend; # upstream đã review riêng, chưa định nghĩa ở đây
}
```

Nếu phía trước là Cloudflare Tunnel/proxy, không tin raw X-Forwarded-For; real-IP trust list phải đúng ingress. Nếu không cấu hình real IP, shared IP limit có thể giới hạn tất cả manager cùng lúc. Proxy có thể trả HTML cho 413/429; JS xử lý theo status nhưng hình dạng JSON qua proxy chưa được xác minh. Slow uploads có thể chiếm slot cho tới khi servlet/ingress timeout; backend semaphore không giải quyết slow-client attack. Ingress cần timeout/connection/body limits phù hợp, kể cả chunked body.

## QA harness và bằng chứng browser

`R2BrowserQaTest` là test opt-in, cấu hình QA riêng (không component scan production), loại trừ datasource/JPA, assert không có AureliaBooksApplication/DataSource/S3Client beans. Dùng embedded Tomcat, controller/SecurityConfig/Thymeleaf thật, catalog/storage doubles trong RAM. Không đại diện đầy đủ cho business services/global advice/layout hoặc transaction MySQL. Không chạy live AWS SDK; không dùng MySQL credentials.

Khởi động từ worktree:

```bash
bash scripts/start-r2-browser-qa.sh
# URL động: target/r2-browser-qa.json; chỉ 127.0.0.1.
# Dừng từ terminal khác:
touch target/r2-browser-qa.stop
```

Tự dừng sau tối đa 15 phút. Tài khoản giả nằm trong source harness, chỉ cho phiên QA loopback; không phải tài khoản thật và không được tái dùng ở môi trường khác. Không log session cookie/CSRF. Fault endpoint `/manager/qa-upload-fault` chỉ ở test classpath, vẫn bảo vệ MANAGER + CSRF; không có trong production artifact.

Phiên browser smoke dùng browser thật và fixtures PNG do agent tạo. Kết quả:

1. Login nội bộ bằng account giả → form Book thật.
2. Upload PNG → local preview, canonical internal URL.
3. Anonymous HTTP GET object trước save → 404 no-store.
4. Upload PNG giả → 400 UI, giữ URL đã upload trước đó.
5. Điền/lưu Book → success redirect; mở edit thấy đúng URL.
6. Stationery upload PNG → URL/preview; file >5 MiB bị client từ chối, vẫn giữ URL; lưu thành công.
7. Anonymous HTTP GET cả Book/Stationery sau save → 200, PNG signature, no-store/nosniff.
8. Actual HTTP chunked multipart >6 MiB, không Content-Length shortcut → servlet 413 JSON, không redirect.
9. Fault memory storage → browser hiển thị 503 message, URL cũ vẫn còn và controls được mở lại.

![QA upload lỗi nhưng URL cũ được giữ](qa-evidence/r2371-upload-error.jpg)

Harness đã dừng. JUnit 1 PASS ở harness chỉ chứng minh safety assertions và vòng đời server; **không đếm nó thành một bài browser E2E tự động**. Các thao tác browser/HTTP phía trên được kiểm chứng riêng bằng công cụ UI và HTTP client. Chưa chạy database transaction E2E, session-expiry browser scenario, actual reverse proxy hoặc live R2. Danh sách catalog trong harness cố ý dùng Page.empty; không dùng nó để xác minh list persistence.

Lượt bootstrap harness đầu phát hiện @TestConfiguration bị SpringBootTest ghép thêm application root; datasource/JPA đã exclude nên context fail an toàn vì thiếu repository. Đã đổi thành cấu hình QA explicit + TestComponent, thêm assertion không có production root trước khi nhận test thao tác. Phiên browser thành công chỉ dùng cấu hình đã sửa. Có một lỗi import User trùng tên trong fixture, đã sửa trước khi server khởi động thành công.

## Kiểm thử hardening

- Lượt đầu hardening: 38 Java tests PASS.
- Regression cuối: **192 tests, 0 failures/errors/skipped**, 19:45:27 +07 ngày 01/11/2026.
- **9 JavaScript tests PASS**, gồm 429 giữ URL/thông báo chờ.
- Harness opt-in kết thúc sạch lúc 19:44:09 +07, không còn listening QA server.
- Không chạy lại MySQL QA; 5 tests MySQL PASS ở R2.36 là bằng chứng lịch sử riêng.
- Chưa full suite, chưa GitHub CI ở commit hiện tại. CI workflow chỉ chạy push main / PR main; feature push không chứng minh CI đã chạy. Auto staging CD chỉ sau main publish/CI; không merge.

Các tests gồm default YAML safety, S3 client timeout config, rate window/reset/principal isolation/bounded map, concurrency/release-on-error, filter order trước CSRF, pause controller/form/GET và toàn bộ regression chọn lọc R2.36. Lệnh regression đầy đủ ghi ở context bàn giao.

## Điều kiện còn lại trước live/staging

- Audit actual env/profile/command-line overrides; giá trị mặc định validate có thể bị Spring external config override. Không khẳng định VPS đang dùng validate khi chưa được đọc/xác minh riêng.
- R2.37.2 còn cần persistence/transaction QA thật, browser session/CSRF expiry, đổi ảnh/shared references và actual proxy matrix. Harness smoke không thay thế các nội dung đó.
- R2.37.3 cần approval bucket/private token scope và key smoke cụ thể, secret storage; chỉ sau khi các điều kiện trước đó đạt.
- Quota là per-JVM fixed window; replica/restart có thể làm quota tổng tăng/reset, boundary có thể burst. Nếu chạy nhiều instances phải dùng shared limiter/edge policy được review. Không thay kiến trúc Monolith hiện tại.
- R2.37.4 review PR/CI riêng, không merge; R2.37.5 deploy/rollback verification cần approval riêng.
