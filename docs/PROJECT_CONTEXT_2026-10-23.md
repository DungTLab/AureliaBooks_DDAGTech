# AureliaBooks — Context bàn giao ngày 23/10/2026

Tài liệu dùng để tiếp tục công việc hoặc cung cấp context cho một cuộc trò chuyện mới.
Trạng thái Git và các điểm triển khai dưới đây được đọc trực tiếp từ workspace ngày 23/10/2026.
Kết quả kiểm thử được ghi kèm thời điểm; không coi kết quả cũ là xác nhận cho commit mới.

## 1. Workspace và trạng thái Git

- Dự án: AureliaBooks_DDAGTech, ứng dụng bán sách của nhóm DDAGTech.
- Workspace: `D:\SpringBoot\AureliaBooks_DDAGTech`.
- Môi trường làm việc: Windows, PowerShell; múi giờ Asia/Saigon.
- Nhánh hiện tại: `main`.
- HEAD: `01a95dd` — `fix(receipts): auto-sanitize invalid input to 1, streamline combobox UX, and align stock alert columns (#31)`.
- `main`, `origin/main`, `origin/HEAD` đang cùng trỏ tới commit này trong Git local.
  Chưa fetch remote trong lần xuất context này, nên không khẳng định remote chưa có commit mới hơn.
- Workspace sạch trước khi tạo tài liệu này. Lần xuất context chỉ thêm tài liệu, không sửa code.
- UC11 đã được merge vào main qua commit `21d2f3d`, PR #30.
- Nhánh local UC11 còn tồn tại: `TrongNP-CheckoutAndOrderPlacement`, tip `433fcc9`
  (`Translate checkout comments to English`). Tracking remote của nhánh báo `gone`.
  Tiếp tục công việc phải kiểm tra nhánh hiện tại, không mặc định vẫn đang ở nhánh UC11.
- Chưa commit hoặc push tài liệu context này.

Các mốc tích hợp gần đây:

| Commit | Nội dung |
| --- | --- |
| `01a95dd` | PR #31: sửa form phiếu nhập, combobox, cột cảnh báo tồn; cập nhật seed users |
| `21d2f3d` | PR #30: UC11 checkout và đặt hàng, engine trừ tồn, comment tiếng Anh |
| `05cb57b` | PR #29: UC21/UC22/UC23 cảnh báo tồn và quản lý nhập hàng |
| `2ee89f3` | PR #26: chỉnh cấu trúc kiến trúc |
| `9204f3d` | PR #25: xác thực OTP/liên hệ tài khoản |
| `593cdc6` | PR #23: UC10 giỏ hàng |

## 2. Công nghệ và kiến trúc

- Java 21, Spring Boot 3.5.16, Maven.
- Spring MVC, Thymeleaf, Spring Security, OAuth2 Client, Bean Validation.
- Spring Data JPA/Hibernate, MySQL, Lombok, audit logging.
- Package gốc: `com.ddagtech.aureliabooks`.
- Tách theo lớp: `controller`, `dto/request`, `dto/response`, `entity`, `mapper`,
  `repository`, `service`, `service/impl`, `security`, `constant`, `exception`.
- Service interface đặt trong `service`; implementation đặt trong `service.impl`.
- Mapper chỉ chuyển/sao chép dữ liệu; nghiệp vụ, transaction và repository do service quản lý.
- `application.yaml`: cổng ứng dụng 8080, JPA `ddl-auto: validate`.
- Schema nguồn: `docker/initdb/01_schema.sql`.
- Migration hiện có: `docker/migrations/20261012_account_contact_verification.sql`.
  Việc có file migration không chứng minh migration đã chạy trên database đang sử dụng.

Trong repository hiện có code cho catalog/sản phẩm, đăng ký/đăng nhập/OTP,
profile và sổ địa chỉ, giỏ hàng, checkout, quản lý tài khoản/quản trị,
audit log, nhà cung cấp/master data, phiếu nhập, lịch sử và cảnh báo tồn.
Đây là kiểm kê code, không phải kết luận tất cả use case đã hoàn thành hoặc đã nghiệm thu.

## 3. Phạm vi của người dùng trong cuộc trò chuyện

- Người dùng Nguyễn Phú Trọng phụ trách UC11 Checkout & Order Placement, trước đó đã làm UC10.
- Người phối hợp: Nguyễn Trần Đức Anh, phụ trách engine kho FND-03 và nhập kho.
- UC11.2 voucher do người khác code. Không tự triển khai voucher hoặc mở rộng sang phần này.
- UC11.1 VNPay thực tế là phần tích hợp riêng; không giả lập thanh toán thành công.
- Người dùng đã yêu cầu giữ nguyên model địa chỉ/tỉnh thành vì thuộc phần người khác.
- Người dùng đã quyết định bỏ điều kiện xác minh SĐT tài khoản trước khi mua hàng.
  Checkout dùng SĐT trong địa chỉ giao hàng, vẫn kiểm tra định dạng hợp lệ.
- Comment đã thêm trong code, HTML, CSS, JavaScript và test phải dùng tiếng Anh.
  Nội dung giao diện vẫn bằng tiếng Việt; trao đổi với người dùng bằng tiếng Việt.
- Chỉ thực hiện commit/push hoặc thao tác xuất bản khi có yêu cầu phù hợp; không tự merge PR.
- Khi người dùng yêu cầu phân tích/họp trước thì chỉ thảo luận, chưa sửa code.

## 4. Quy tắc nghiệp vụ cần giữ

- COD và VNPay đều trừ tồn ngay lúc tạo đơn, cùng transaction với order/items.
- Không giữ chỗ tồn trong giỏ và không tạo reservation riêng.
- COD mới: `PENDING_CONFIRMATION / UNPAID`.
- VNPay mới: `PENDING_PAYMENT / UNPAID`.
- Trạng thái đơn và trạng thái thanh toán độc lập.
- Thanh toán thành công không được trừ tồn lần hai.
- Marker `stock_deducted_at` và `stock_restored_at` bảo vệ các thao tác kho khỏi thực hiện lặp.
- Chỉ hoàn tồn cho đơn đã thực sự trừ kho và theo nghiệp vụ hủy/nhận lại hàng hợp lệ.
- Không tự hủy đơn sau 15 phút; đối soát/thanh toán và vòng đời đơn thuộc các luồng tương ứng.
- Không INSERT bảng stock movement log riêng; lịch sử kho của dự án dùng chứng từ/view hiện có.
- Lưu snapshot tên sản phẩm, giá, người nhận, SĐT và địa chỉ tại lúc đặt hàng.
- Giá/số lượng/thông tin giao hàng đổi sau review phải yêu cầu khách xác nhận lại.
- Gửi lại cùng request không được tạo đơn mới hoặc xóa giỏ khách vừa bổ sung.
- Cước Bắc 50.000đ; Trung/Tây Nguyên 40.000đ; Nam/Mekong 30.000đ.
- Ngưỡng miễn ship và tương tác với SHIPPING voucher chưa chốt; chưa tự áp dụng miễn ship.

## 5. UC11 hiện đã triển khai

Luồng COD đã nối: giỏ hàng → checkout → review → tạo đơn → kết quả/in xác nhận.

- Chọn địa chỉ đã lưu hoặc thêm địa chỉ qua `AddressService.create` của UC09.
  Địa chỉ thêm trên checkout được lưu vào sổ; vẫn áp dụng giới hạn tối đa 5 địa chỉ.
- Hiển thị sản phẩm, phí ship theo vùng, tổng tiền và trạng thái phương thức thanh toán.
- Không yêu cầu tài khoản có SĐT hoặc `phoneVerified=true` để checkout.
- SĐT nhận hàng lấy từ `ShippingAddress.phone`, lưu vào `orders.shipping_phone`.
- Không tin userId, giá/tổng tiền hay confirmation do trình duyệt gửi.
- Bản review do server tạo và lưu trong session, tối đa 8 bản theo token riêng cho các tab.
- Token UUID bỏ dấu gạch nối, 32 ký tự hex thường, đồng thời là `orders.order_code` có unique constraint.
- Request gửi lại của đúng owner/địa chỉ/phương thức trả đơn đã commit trước khi kiểm tra giỏ hoặc review cũ.
- Đơn không tồn tại hoặc không thuộc khách hiện tại trả 404.
- Lỗi đọc trang checkout trả 503; không giả lập giỏ trống hoặc tạo vòng lặp redirect.
- Giao diện dùng chung fragment sản phẩm/tổng tiền, hỗ trợ mobile và in xác nhận đơn.

### Ranh giới còn chờ

- `CheckoutPaymentGateway` mới là interface, chưa có implementation VNPay trong source hiện tại.
  Không có adapter hoặc adapter chưa sẵn sàng thì khóa VNPay trên UI và chặn POST trước tạo đơn.
- Backend placement hỗ trợ cả COD/VNPay; điều này không đồng nghĩa cổng VNPay thật đã hoạt động.
- Adapter sau này phải kiểm tra owner/trạng thái, đọc số tiền đã lưu và trả URL HTTPS đã ký.
  Mở cổng thất bại sau commit phải giữ đơn để thử lại.
- Voucher khác rỗng hiện bị từ chối rõ ràng; chưa có xử lý tính/ghi nhận lượt dùng trong checkout.
- Không tự mở rộng province/district/ward hoặc tự ánh xạ province sang vùng ship.
  `province` hiện là chuỗi, `economicRegion` là enum riêng; tính nhất quán giữa hai trường là điểm đã trao đổi,
  chưa xử lý theo yêu cầu giữ nguyên phần của người khác.

## 6. Cấu trúc trừ kho đã thống nhất

```text
CheckoutController
  → CheckoutSessionStore: đọc bản review thuộc session và owner
  → OrderPlacementServiceImpl.placeOrder: transaction READ_COMMITTED
      1. Kiểm tra request/voucher, khóa và refresh owner
      2. Trả đơn cũ nếu token đã commit và các ràng buộc khớp
      3. Kiểm tra bản review do server lưu
      4. Khóa/refresh sản phẩm theo ID tăng dần, kiểm tra giỏ và giá
      5. Đọc/kiểm tra địa chỉ của owner và SĐT nhận hàng
      6. Tính lại và đối chiếu với review
      7. Lưu order/items và snapshot
      8. StockMutationService.deductOrder(orderId)
          → transaction REQUIRED, tham gia transaction của caller
          → khóa/refresh order trước khi kiểm tra marker
          → đã có stock_deducted_at thì bỏ qua
          → gom số lượng theo sản phẩm, khóa/refresh sản phẩm
          → ProductRepository.deductStockIfAvailable
             UPDATE ... WHERE active AND quantity > 0 AND stock >= quantity
          → phải cập nhật đúng 1 dòng, nếu không thì ném lỗi
          → refresh sau bulk UPDATE
          → ghi stock_deducted_at và audit ORDER_DEDUCT_STOCK
      9. Xóa dòng giỏ bằng orphanRemoval
     10. Ghi audit ORDER_PLACED
```

- Checkout không trực tiếp gọi query trừ kho và không tự ghi marker.
- SQL/JPQL vẫn nằm trong repository; stock engine điều phối nghiệp vụ trừ kho.
- Hai audit kho và đặt hàng có ý nghĩa riêng nhưng cùng transaction.
- Lỗi trừ dòng thứ hai, audit engine hoặc audit đặt hàng phải rollback cả order/items/stock/cart/audit.
- Checkout vẫn khóa sản phẩm trước khi lưu order_items để bảo vệ giá snapshot và tránh tranh chấp
  nâng shared lock của foreign key. Không bỏ cơ chế này tùy tiện khi refactor.
- Refresh cần thiết vì query có khóa không tự thay giá trị entity đã nằm trong persistence context.
- Chỉ engine đang gọi `deductStockIfAvailable` và ghi `stockDeductedAt` trong source hiện tại.

## 7. Các file UC11 cần đọc

Gốc Java: `D:\SpringBoot\AureliaBooks_DDAGTech\src\main\java\com\ddagtech\aureliabooks`.

```text
controller/
  CheckoutController.java
  CheckoutSessionStore.java
dto/
  CheckoutConfirmation.java
  request/CheckoutSelectionRequest.java
  request/CheckoutRequest.java
  response/CheckoutView.java
  response/OrderConfirmationView.java
entity/
  Order.java
  OrderItem.java
mapper/
  OrderMapper.java
repository/
  OrderRepository.java
  ProductRepository.java
service/
  OrderPlacementService.java
  CheckoutPaymentGateway.java
  StockMutationService.java
  impl/OrderPlacementServiceImpl.java
  impl/StockMutationServiceImpl.java
```

UI nằm dưới `D:\SpringBoot\AureliaBooks_DDAGTech\src\main\resources`:

```text
templates/checkout/{view,review,order-confirm,fragments,load-error}.html
static/css/checkout.css
static/js/checkout.js
templates/cart/view.html
templates/fragments/navbar.html
```

Các thành phần dùng chung: `CustomerIdentity`, `CartService`, `AddressService`,
`AuditLogService`, `CartMapper`, `ShippingAddressMapper`, các repository user/cart/address.

Routes:

| Method | Route | Chức năng |
| --- | --- | --- |
| GET | `/checkout` | Chọn địa chỉ/phương thức, xem tiền |
| POST | `/checkout/addresses` | Thêm địa chỉ qua UC09 |
| POST | `/checkout/review` | Lưu review và cấp token |
| GET | `/checkout/review/{token}` | Xem bản review đã lưu |
| POST | `/checkout/place` | Tạo đơn hoặc trả đơn cũ cho replay |
| GET | `/orders/{orderCode}` | Xem/in snapshot đơn thuộc owner |
| POST | `/orders/{orderCode}/pay` | Thử thanh toán đơn chờ qua adapter |

## 8. Kiểm thử và giới hạn của bằng chứng

Các file trong `src/test/java/com/ddagtech/aureliabooks`:

- `controller/CheckoutControllerTest.java`: Thymeleaf thật, service mock; CSRF, quyền sở hữu,
  form, session, adapter và trang kết quả.
- `controller/CheckoutSessionStoreTest.java`: owner/session, nhiều tab, tái sử dụng token và giới hạn review.
- `service/OrderPlacementServiceTest.java`: kiểm tra nghiệp vụ và gọi stock engine.
- `service/StockMutationServiceTest.java`: contract trừ kho, replay, conditional update, gom dòng sản phẩm.
- `service/OrderPlacementMySqlTest.java`: rollback, tranh mua, trùng token, snapshot, marker và entity cũ.
- `service/CheckoutFlowMySqlTest.java`: Security → MVC → service → MySQL thật.

Kết quả lịch sử đã chạy trong cuộc trò chuyện:

- 20/10/2026, bộ hồi quy trước bổ sung kiểm tra order entity cũ: 498 test, 0 failure, 0 error, 27 skipped.
- 20/10/2026, lần cuối sau sửa marker refresh: 58 test checkout/engine/MySQL, 0 failure, 0 error, không skipped.
  Trong đó có 15 test MySQL: 11 transaction/đồng thời và 4 luồng web.
- Sau khi đổi comment sang tiếng Anh: đối chiếu token code/literal và nội dung HTML;
  chỉ thay comment. Không chạy lại toàn bộ test cho lần dịch comment.
- Hôm nay 23/10/2026 chưa chạy test trên `main` tại `01a95dd`.
  Nhánh main đã tích hợp thêm code nhập kho; cần chạy kiểm thử phù hợp trước khi khẳng định trạng thái mới.

Test MySQL bật bằng `-Dcheckout.mysql=true`; tự tạo database `uc11_qa_<uuid>`,
chỉ dùng MySQL cục bộ, kiểm tra tên rồi xóa đúng database QA. Không dùng database ứng dụng làm fixture.

Ví dụ lệnh Maven:

```powershell
mvn '-Dtest=OrderPlacementServiceTest,StockMutationServiceTest,CheckoutControllerTest,CheckoutSessionStoreTest' test
mvn '-Dtest=OrderPlacementMySqlTest,CheckoutFlowMySqlTest' '-Dcheckout.mysql=true' test
```

Lưu ý môi trường đã gặp:

- `mvn` có thể dùng nhầm Java qua Oracle javapath; JDK đã chạy ổn là `C:\Program Files\Java\jdk-21`.
- Maven cài tại `C:\Program Files\apache-maven-3.9.16`; dependency trước đây đã cache, có thể dùng offline nếu còn đủ.
- Mockito agent đã dùng: `C:\Users\nigor\.m2\repository\org\mockito\mockito-core\5.17.0\mockito-core-5.17.0.jar`.
  Có thể truyền `-DargLine=-javaagent:<đường dẫn jar>` nếu attach bị chặn.
- Sandbox từng chặn kết nối MySQL local; khi xảy ra cần xin quyền thực thi phù hợp, không đổi fixture sang DB thật.
- Test toàn dự án có thể ghi lại `docs/registration/rendered-register.html`.
  Kiểm tra diff để không đưa artifact sinh tự động không liên quan vào thay đổi.
- Không chạy toàn bộ schema có DROP/CREATE DATABASE trên database có dữ liệu cần giữ.
- Không in mật khẩu, secret hoặc toàn bộ cấu hình datasource khi chẩn đoán.

## 9. Nguồn context và cách tiếp tục

- Tài liệu UC11 trong repo: `D:\SpringBoot\AureliaBooks_DDAGTech\docs\checkout\UC11.md`.
- Tài liệu UC10: `D:\SpringBoot\AureliaBooks_DDAGTech\docs\cart\UC10.md`.
- Tài liệu nghiệp vụ người dùng cung cấp trước đây:
  `C:\Users\nigor\Downloads\0. Nghiệp vụ.docx.md`.
- Đặc tả UC11 được cung cấp trước đây:
  `C:\Users\nigor\.codex\attachments\2a491433-fa09-47e5-8cb5-a57b9c6cb9cc\Pasted text.txt`.
- Một ảnh review từng dẫn `GEMINI.md`, nhưng không tìm thấy file này trong workspace khi kiểm tra.
  Không coi nội dung được trích trong ảnh là bằng chứng đã đọc quy tắc repository.
  Quyết định tập trung trừ tồn về stock engine sau đó đã được người dùng chấp thuận và đã triển khai.

Chưa có yêu cầu triển khai tiếp sau khi xuất context. Bước tiếp theo phụ thuộc task mới của người dùng.
Trước khi sửa: kiểm tra Git, đọc code hiện tại và các hướng dẫn repository nếu được bổ sung;
giữ phạm vi UC11 và các ranh giới sở hữu đã thống nhất. Không làm lại phần UC11 đã merge vào main.
