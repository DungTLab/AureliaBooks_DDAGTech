# Ảnh sản phẩm trên Cloudflare R2

## Phạm vi

Backend hỗ trợ upload ảnh MANAGER, private storage, GET có visibility check, form sách/văn phòng phẩm và lưu URL. R2 tắt mặc định; avatar giữ nguyên. Không thay schema/entity/migration. Không chạy ứng dụng trỏ database thật với `ddl-auto=update`; không dùng full application startup để test. Các tests MVC dùng mocks, JPA test riêng dùng QA local và `validate`.

## Luồng sử dụng

Khi R2 và upload được bật, MANAGER chọn PNG/JPEG trên form, xem trước local và bấm **Tải ảnh lên**. Browser gửi multipart `file`, cookie cùng origin, CSRF token và AJAX header tới `POST /manager/products/images`. Chỉ MANAGER được phép; ADMIN không mặc nhiên có quyền. Multipart boundary để browser tự tạo.

HTTP 201 JSON `{ "imageUrl": "/products/images/<uuid-v4>" }` cập nhật field `mainImageUrl`. Không gửi object key, endpoint hay credentials cho client. Upload chưa ghi DB. Phải lưu sản phẩm để ảnh được GET công khai; orphan vẫn trả 404. Giới hạn: input/output 5 MiB, 16 triệu pixel, PNG/JPEG decode được, resize tối đa 1600px và encode lại PNG. Tên file/MIME client không được tin cậy.

Browser giữ URL cũ nếu upload lỗi, không tự retry và không cho submit trong lúc upload. Preview dùng blob local, không gọi public GET orphan. Nếu validation form lỗi, URL đã upload giữ trong form để thử lưu lại; preview local không sống qua reload. Nếu bỏ trang, object vẫn private.

Upload trả JSON 400 cho input/multipart lỗi, 413 cho servlet multipart size exception, 503 khi storage lỗi; no-store/nosniff, không lộ cause. Security trả 401/403 cho AJAX sai auth/role/CSRF. MVC tests có mock MultipartResolver để xác minh lỗi xảy ra trước controller dispatch; đây chưa phải thử nghiệm multipart của servlet container thật.

Khi R2 tắt, cả GET/POST controller không đăng ký; form ẩn uploader và giữ input URL thủ công. Không thay URL ảnh cũ. Backend vẫn chấp nhận giữ nguyên internal URL cũ khi sửa sản phẩm, nhưng không cho gắn một internal URL mới nếu storage tắt.

## Quy tắc URL khi lưu

Cả bốn luồng tạo/sửa Book/Stationery đi qua ProductImageReferencePolicy trước khi mutate/save. Form strip khoảng trắng đầu/cuối. URL mới tối đa 500 ký tự, chỉ chấp nhận:

- HTTP/HTTPS có hostname, không user-info/backslash/control characters; không fetch URL ngoài ở backend.
- `/products/images/<UUID-v4 lowercase>` chính xác; không query/fragment/traversal, không raw S3 object key hoặc S3 API endpoint Cloudflare.
- Trống/null để bỏ ảnh.

Internal URL mới phải đọc được từ storage trước khi ghi DB. Object thiếu → lỗi input; storage tắt/lỗi → từ chối lưu với thông báo có thể thử lại. Không kiểm tra visibility trước khi gắn ảnh mới, vì ảnh chưa có tham chiếu phải là private orphan.

URL không đổi được giữ lại, kể cả định dạng legacy, không gọi R2. Không rewrite hàng loạt URL cũ. Existing external URLs giữ nguyên cả query string; server không tải URL ngoài để xác minh nội dung. Xác minh internal object không chống được việc operator xóa object trực tiếp sau đó; quyền xóa bucket phải được kiểm soát. Manager được phép dùng lại một internal URL có object tồn tại; không có ownership riêng theo người upload.

## Object lifecycle — chính sách hiện tại

**Không tự động xóa object, không có DELETE API hay bucket expiration rule.** Không xóa ảnh cũ trong transaction DB, khi upload mới, khi form validation/DB save thất bại, khi ẩn sản phẩm hoặc khi thay ảnh. Không có tác vụ scheduled GC. Giữ ảnh private là lựa chọn bảo toàn dữ liệu khi schema đang freeze.

| Tình huống | Kết quả |
|---|---|
| Upload xong rồi bỏ form/lưu DB lỗi | Object private chưa tham chiếu, GET 404 |
| Thay ảnh và lưu thành công | URL mới lưu DB; ảnh cũ giữ lại |
| Nhiều sản phẩm dùng chung ảnh | Không xóa; GET chỉ cần ít nhất một product/category active |
| Ẩn product/category | GET kiểm tra lại mỗi lần; không cache, trả 404 nếu không còn tham chiếu active |
| Bỏ URL khỏi product | Object vẫn private; GET 404 nếu không còn tham chiếu active |
| PUT trả lỗi sau khi R2 có thể đã ghi | Có thể còn orphan; không giả định upload lỗi nghĩa là không có object |

Đánh đổi: dung lượng/cost tăng cho tới khi có đợt đối soát. R2.35 là thiết kế lifecycle an toàn, không phải triển khai automatic garbage collection.

### Quy trình đối soát/xóa về sau — chưa thực thi

1. Được phê duyệt riêng cho đúng bucket/môi trường. Kiểm kê dry-run chỉ prefix `products/` và keys UUID chuẩn, có timestamp/size.
2. Lấy **mọi** `main_image_url` trong DB tương ứng, bao gồm product/category inactive; không dùng visibility count để quyết định xóa. Bảo toàn ảnh dùng chung và khả năng re-enable.
3. Chỉ đề xuất ứng viên không có tham chiếu và quá thời gian chờ đã thống nhất (đề xuất tối thiểu 7 ngày). Grace period không tự loại được race với manager đang giữ form cũ.
4. Trước khi xóa: tạm dừng upload và lưu/thay ảnh sản phẩm, kết thúc/expire form đang mở, đối chiếu lại toàn bộ DB trong cửa sổ bảo trì. Không chỉ check count rồi delete trong hệ thống vẫn ghi tự do.
5. Review danh sách cụ thể, có bản sao/khôi phục và audit phù hợp; operator mới thực hiện delete theo phê duyệt. Nếu không tạo được cửa sổ an toàn, giữ object.

Không dùng bucket lifecycle expiration theo tuổi cho toàn bộ `products/`, vì có thể xóa cả ảnh đang sử dụng. Automatic GC cần cơ chế đồng bộ claim/reference, retention và audit bền vững, phải thiết kế lại cùng nhóm nếu cần schema mới.

## Kiểm thử và giới hạn

Regression chạy danh sách test cụ thể, không full suite. Lệnh và số tests thực chạy ghi trong `docs/AureliaBooks_R2_Chat_Context.md`. `scripts/test-r2-mysql.sh` chỉ dùng container QA loopback đã có canonical schema, với credentials ngoài Git; không tự import hay tạo lại container.

JavaScript tests: `scripts/tests/product-image-upload.test.mjs` export `runTests()` cho Node ESM. Ví dụ trên máy có Node:

```bash
node --input-type=module -e "import('./scripts/tests/product-image-upload.test.mjs').then(async m => console.log(await m.runTests()))"
```

Các tests này dùng DOM/fetch giả, không phải browser E2E. Phiên triển khai chạy bằng Node tool runtime vì CLI `node` không có trên máy. MVC form tests render Thymeleaf thật; chưa tự động hóa tương tác trên browser/thiết bị thật.

## Điểm dừng R2.37

Chưa chạy R2 live smoke, mở PR, merge hoặc deploy Staging/Production trong phạm vi này. Trước R2.37 cần phê duyệt cụ thể và kiểm tra:

- Credentials secret storage, bucket private, token đúng scope, region/endpoint; không log secrets.
- Default DDL đã đổi sang validate và bỏ JDBC tự tạo DB ở R2.37.1; vẫn phải kiểm tra env/profile overrides thật trước khi kết nối DB freeze.
- Live Java PUT/GET với ảnh QA và cleanup key cụ thể được duyệt; không scan/delete dữ liệu thật.
- Browser E2E cả hai form: CSRF/session expiry, validation retry, lưu/đổi ảnh, visibility, proxy/context path, multipart limits tại servlet/proxy thật.
- Quota/rate limiting upload tại gateway hoặc backend trước mở cho môi trường dùng thật. Client single-flight không thay server rate limit. R2.37.1 đã thêm quota 10 requests/phút/principal và 2 concurrent uploads/JVM; vẫn cần review ingress và shared limits nếu nhiều instance.
- Pause upload bằng R2_UPLOAD_ENABLED=false trong khi giữ R2_ENABLED=true để không mất GET ảnh. Tắt toàn R2 vẫn làm ảnh internal ngừng phục vụ; không xem đó là rollback an toàn sau khi có dữ liệu. Xem R2_RELEASE_READINESS.md.

## Cập nhật hardening R2.37.1

Servlet request limit 6 MiB, file 5 MiB, disk threshold 0. Backend admission trước CSRF kiểm soát 10 attempts/phút/principal và 2 concurrent requests/JVM, 429 + Retry-After. SDK call timeout 30s / attempt 10s. R2_UPLOAD_ENABLED=false tắt upload và ẩn uploader, giữ GET/storage nếu R2_ENABLED=true. Chi tiết bằng chứng/giới hạn/rollback: R2_RELEASE_READINESS.md. Chưa thay proxy, connect R2 thật, PR hay deploy.
