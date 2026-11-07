# Workflow 3 — Sale Staff xử lý đơn hàng và in đóng gói

## Chuẩn bị

Chức năng dùng khóa bi quan trên bản ghi đơn hàng, không yêu cầu thêm cột hoặc chạy migration database.

Đăng nhập bằng Sale Staff hoặc Admin để xử lý đơn và xác nhận hoàn tiền. Manager được xem đơn và hủy đơn đủ điều kiện, không được duyệt/giao/xác nhận hoàn tiền. Tài khoản phải còn hoạt động. Mở `/staff/orders`; Sale Staff cũng có mục **Xử lý đơn hàng** trong menu tài khoản.

## 1. Tìm đơn cần xử lý

Chọn trạng thái đơn, trạng thái thanh toán, phương thức thanh toán hoặc khoảng ngày đặt rồi bấm **Lọc đơn**. Hai ngày đầu/cuối đều được tính. **Xóa bộ lọc** trở về toàn bộ hàng đợi. Đơn đặt sớm nằm trước; sử dụng Trang trước/Trang sau để xem thêm, bộ lọc được giữ nguyên.

Mỗi dòng có mã đơn, ngày đặt, khách hàng, người nhận, trạng thái, thanh toán, tổng tiền và **Xử lý đơn**. Đơn `PENDING_PAYMENT` không xuất hiện và không thể mở chi tiết bằng đường dẫn trực tiếp trong hàng đợi này. Khi không có kết quả, đổi bộ lọc hoặc xóa bộ lọc. Bộ lọc thanh toán **Chưa hoàn tiền** chỉ lấy đơn CANCELLED/RETURNED còn PAID. Đơn hủy hoặc đã trả hàng (RETURNED) còn PAID có nhãn **Cần hoàn tiền**; nhãn biến mất sau khi ghi nhận REFUNDED.

## 2. Kiểm hàng và in phiếu đóng gói

Bấm **Xử lý đơn**. Đối chiếu tên người nhận, SĐT, địa chỉ, mã vận đơn (nếu có), từng sản phẩm và số lượng. Thông tin được lấy từ đơn đã lưu lúc đặt, không thay bằng địa chỉ hoặc tên/giá mới trong danh mục.

Tích **Đã kiểm** sau khi kiểm đủ từng dòng. Bấm **In phiếu đóng gói**, chọn máy in hoặc lưu PDF. Phiếu in giữ thông tin nhận hàng, sản phẩm/SL, thanh toán và dòng ký người đóng gói; các nút thao tác và thanh điều hướng được ẩn. Dấu kiểm trên trang không lưu thành tiến độ đóng gói khi tải lại.

## 3. Duyệt và giao hàng

1. Đơn **Chờ xác nhận**: kiểm tra đơn rồi bấm **Đã xác nhận**. Hệ thống lưu thời điểm xác nhận.
2. Đơn **Đã xác nhận**: kiểm và đóng gói, nhập mã vận đơn nếu có rồi bấm **Đang giao hàng** khi bàn giao cho đơn vị vận chuyển. Hệ thống lưu thời điểm bàn giao.
3. Đơn **Đang giao hàng**: chỉ bấm **Giao thành công** khi có xác nhận khách đã nhận. Với COD, bắt buộc tích xác nhận đã giao và thu đủ tiền; hệ thống cập nhật `DELIVERED + PAID` cùng transaction. Không xác nhận đã thu tiền thì không cập nhật. VNPay giữ trạng thái PAID.

Không thể nhảy từ Chờ xác nhận sang Giao thành công hoặc quay lại trạng thái trước. Nếu hai nhân viên cùng xử lý một đơn, thao tác sau chờ thao tác trước hoàn tất rồi kiểm tra trạng thái mới nhất. Nếu bước đó đã được thực hiện hoặc không còn hợp lệ, hệ thống chặn chuyển trạng thái. Nếu không lấy được khóa, hệ thống yêu cầu thử lại. Về hàng đợi, mở lại đơn và kiểm tra trạng thái mới trước khi tiếp tục.

VNPay phải PAID, COD phải UNPAID khi xác nhận/chuẩn bị giao. Staff không dùng nút chuyển trạng thái để giả lập thanh toán VNPay. COD hiển thị số tiền thu hộ khi đơn còn chưa thanh toán. Mỗi form yêu cầu xác nhận trước khi gửi; bấm Hủy trong hộp xác nhận giữ nguyên dữ liệu.

## 4. Hủy đơn và hoàn kho

Chỉ hủy đơn trong hàng đợi khi chưa giao vận chuyển: Chờ xác nhận hoặc Đã xác nhận. Mở **Hủy đơn hàng**, nhập lý do cụ thể (1–255 ký tự), sau đó bấm **Xác nhận hủy và hoàn kho**. Đơn phải có ghi nhận đã trừ kho khi đặt.

Hệ thống lưu người thao tác/lý do, thời điểm hủy, hoàn tồn nếu trước đó đã trừ kho và ghi audit. Không hoàn kho hai lần. Đơn đã giao vận chuyển phải đi theo luồng trả hàng riêng.

Đơn VNPay chưa rõ kết quả giao dịch cần đối soát trước khi hủy. Đơn đã thanh toán khi hủy vẫn giữ trạng thái thanh toán; hoàn tiền cần xử lý theo luồng riêng.

## 5. Xác nhận nhận lại hàng hoàn và hoàn kho (POP-DB-02)

Sale Staff/Admin mở đơn đã giao thành công (DELIVERED) khi khách hoàn trả toàn bộ. Khối **Xác nhận nhận lại hàng hoàn** chỉ hiển thị khi đơn đang ở trạng thái phù hợp, đã trừ tồn và chưa từng hoàn tồn.

Nhân viên kiểm đếm hàng thực tế, nhập lý do hoàn hàng (bắt buộc) và ghi chú kiểm hàng (nếu có), tích xác nhận đã nhận lại đủ 100% số lượng các mặt hàng trong đơn, sau đó bấm **Xác nhận nhận hàng & Hoàn tồn kho**. Hệ thống tự động hoàn tồn kho đúng một lần, ghi nhận thời điểm, nhân viên xác nhận và chuyển trạng thái đơn sang RETURNED. Theo Baseline V2.4, nghiệp vụ này được tích hợp trực tiếp tại đây thay thế cho UC24 trước đây.

## 6. Xác nhận khoản hoàn tiền đã thực hiện

Sale Staff/Admin mở đơn hủy đã PAID hoặc đơn RETURNED đã có xác nhận nhận lại toàn bộ hàng hoàn thành công. Form **Xác nhận hoàn tiền** chỉ hiện khi đơn đủ điều kiện và chưa có xác nhận hoàn trước đó.

Nhập đúng toàn bộ tổng tiền của đơn, phương thức hoàn (Tiền mặt mặc định hoặc Chuyển khoản), ngày hoàn tất (mặc định ngày hiện tại, có thể chọn lại nhưng không ở tương lai), thông tin chứng minh. Tích xác nhận đã hoàn tiền thực tế bên ngoài hệ thống, rồi bấm **Ghi nhận hoàn tiền** và xác nhận. Không dùng chức năng này để thực hiện chuyển tiền.

Thành công: payment status chuyển thành REFUNDED, trạng thái đơn và tồn kho giữ nguyên. Lịch sử hiển thị nhân viên, thời điểm ghi nhận, số tiền, phương thức, ngày hoàn tất và chứng từ. Thông tin thiếu, sai số tiền, hoàn một phần hoặc hoàn trùng đều bị từ chối. Thao tác hoàn tiền độc lập với hoàn kho và không cộng lại tồn kho.

## 7. Kiểm tra audit

Trong công cụ audit hiện có, lọc action `ORDER_STATUS_CHANGED` hoặc `ORDER_REFUND_CONFIRMED`, bảng `orders` và ID đơn để xem nhân viên, trạng thái đơn/thanh toán trước/sau, lý do, vận đơn hoặc chứng từ hoàn tiền. Khi thực sự hoàn kho do hủy còn có action `ORDER_CANCELLED_RESTOCK`. Các thông tin này cũng có trong phần lịch sử tại chi tiết đơn.

## Danh sách ảnh nghiệm thu cần chụp khi chạy thật

1. Hàng đợi có dữ liệu và các bộ lọc.
2. Phiếu đóng gói đầy đủ người nhận, SĐT, địa chỉ, sản phẩm/SL.
3. Bản xem trước khi in.
4. Luồng xác nhận → giao → giao thành công và các thời điểm.
5. Hủy đơn có lý do và audit; kiểm tra số lượng tồn đã hoàn.
6. Hai staff cùng bấm xác nhận một đơn: một người thành công, thao tác còn lại bị chặn vì đơn đã xác nhận; audit chỉ ghi một lần.

Danh sách trên là checklist nghiệm thu; không phải khẳng định đã tạo ảnh chụp.
