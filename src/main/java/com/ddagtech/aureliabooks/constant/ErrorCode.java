package com.ddagtech.aureliabooks.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Stable application error identifiers with Vietnamese user-facing messages.
 * Business-rule references belong in documentation, not client messages.
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    // 9xxx: System & Infrastructure Errors (2 codes)
    UNCATEGORIZED_EXCEPTION(9999, "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(9001, "Dữ liệu yêu cầu không hợp lệ.", HttpStatus.BAD_REQUEST),

    // 1xxx: Authentication, OTP & Authorization — Lê Tiến Dũng & Trần Huỳnh Giác (17 codes)
    UNAUTHENTICATED(1001, "Bạn cần đăng nhập để tiếp tục.", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(1002, "Bạn không có quyền truy cập nội dung này.", HttpStatus.FORBIDDEN),
    USER_EXISTED(1003, "Email hoặc số điện thoại này đã được đăng ký.", HttpStatus.CONFLICT),
    USER_NOT_EXISTED(1004, "Không tìm thấy tài khoản.", HttpStatus.NOT_FOUND),
    INVALID_CREDENTIALS(1005, "Email, số điện thoại hoặc mật khẩu không đúng.", HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(1006, "Tài khoản đã bị khóa hoặc ngừng hoạt động.", HttpStatus.FORBIDDEN),
    PASSWORD_NOT_MATCH(1007, "Mật khẩu hiện tại không đúng.", HttpStatus.BAD_REQUEST),
    DOB_IMMUTABLE(1009, "Không thể thay đổi ngày sinh đã lưu.", HttpStatus.BAD_REQUEST),
    INVALID_ROLE_ASSIGNMENT(1010, "Vai trò được chọn không hợp lệ cho tài khoản nhân viên.", HttpStatus.BAD_REQUEST),
    CANNOT_LOCK_SELF(1011, "Bạn không thể tự vô hiệu hóa tài khoản của mình.", HttpStatus.BAD_REQUEST),
    CANNOT_REVOKE_LAST_ADMIN(1012, "Không thể vô hiệu hóa hoặc đổi vai trò của quản trị viên cuối cùng đang hoạt động.", HttpStatus.CONFLICT),
    OTP_INVALID_OR_EXPIRED(1013, "Mã xác thực OTP không chính xác hoặc đã hết hạn.", HttpStatus.BAD_REQUEST),
    OTP_MAX_ATTEMPTS_EXCEEDED(1014, "Bạn đã nhập sai mã OTP quá 5 lần. Vui lòng yêu cầu gửi mã mới.", HttpStatus.TOO_MANY_REQUESTS),
    OTP_RESEND_COOLDOWN(1015, "Vui lòng đợi ít nhất 60 giây trước khi yêu cầu gửi lại mã OTP.", HttpStatus.TOO_MANY_REQUESTS),
    OTP_DELIVERY_FAILED(1016, "Không thể gửi mã xác thực OTP lúc này. Vui lòng thử lại sau.", HttpStatus.SERVICE_UNAVAILABLE),
    GOOGLE_IDENTITY_CONFLICT(1017, "Tài khoản Google này đã được liên kết với một tài khoản khác.", HttpStatus.CONFLICT),
    PHONE_REQUIRED_BEFORE_CHECKOUT(1018, "Vui lòng bổ sung và xác minh số điện thoại trước khi đặt hàng.", HttpStatus.BAD_REQUEST),

    // 2xxx: Address & Shipping Constraints — Lê Tiến Dũng (3 codes)
    ADDRESS_QUOTA_EXCEEDED(2001, "Bạn chỉ có thể lưu tối đa 5 địa chỉ giao hàng đang hoạt động.", HttpStatus.BAD_REQUEST),
    ADDRESS_NOT_FOUND(2002, "Không tìm thấy địa chỉ giao hàng.", HttpStatus.NOT_FOUND),
    ADDRESS_LOCKED_ACTIVE_ORDER(2003, "Không thể xóa địa chỉ đang được sử dụng cho đơn hàng chưa hoàn tất.", HttpStatus.BAD_REQUEST),

    // 3xxx: Generic & Resource Validation — Shared (2 codes)
    RESOURCE_NOT_FOUND(3001, "Không tìm thấy dữ liệu yêu cầu.", HttpStatus.NOT_FOUND),
    INVALID_INPUT_DATA(3002, "Thông tin nhập chưa hợp lệ. Vui lòng kiểm tra lại.", HttpStatus.BAD_REQUEST),
    /** Review không khớp dữ liệu hiện tại hoặc token không hợp lệ: yêu cầu khách xác nhận lại trước khi tạo đơn. */
    CHECKOUT_CHANGED(3003, "Thông tin đặt hàng đã thay đổi. Vui lòng kiểm tra và xác nhận lại.", HttpStatus.CONFLICT),

    // 4xxx: Catalog & Master Data Management — Huỳnh Nhật Duy (6 codes)
    PRODUCT_NOT_FOUND(4001, "Không tìm thấy sản phẩm hoặc sản phẩm đã ngừng kinh doanh.", HttpStatus.NOT_FOUND),
    DUPLICATE_BARCODE(4002, "Mã vạch (Barcode) này đã tồn tại trên hệ thống.", HttpStatus.CONFLICT),
    DUPLICATE_ISBN(4003, "Mã ISBN này đã được đăng ký cho một đầu sách khác.", HttpStatus.CONFLICT),
    DUPLICATE_MASTER_DATA_NAME(4004, "Tên danh mục, nhà xuất bản hoặc thương hiệu đã tồn tại.", HttpStatus.CONFLICT),
    CATEGORY_CYCLE_DETECTED(4005, "Không thể chọn danh mục cha tạo thành vòng lặp phân cấp.", HttpStatus.BAD_REQUEST),
    MASTER_DATA_IN_USE(4006, "Không thể xóa dữ liệu đang được tham chiếu bởi sản phẩm hoặc danh mục con.", HttpStatus.CONFLICT),

    // 5xxx: Warehouse, Goods Receipts & Inventory Posting — Nguyễn Trần Đức Anh (9 codes)
    RECEIPT_NOT_FOUND(5001, "Không tìm thấy phiếu nhập kho.", HttpStatus.NOT_FOUND),
    RECEIPT_ALREADY_FINALIZED(5002, "Phiếu nhập kho đã chốt (RECEIVED) không thể chỉnh sửa hoặc xóa.", HttpStatus.CONFLICT),
    RECEIPT_EMPTY_ITEMS(5003, "Phiếu nhập kho phải có ít nhất một dòng sản phẩm hợp lệ.", HttpStatus.BAD_REQUEST),
    FIRST_IMPORT_LIMIT_EXCEEDED(5004, "Số lượng nhập lần đầu vượt quá hạn mức cho phép (tối đa 100 sản phẩm, hoặc 500 cuốn đối với sách giáo khoa).", HttpStatus.BAD_REQUEST),
    INSUFFICIENT_STOCK(5005, "Số lượng tồn kho khả dụng không đủ để đáp ứng yêu cầu.", HttpStatus.CONFLICT),
    SUPPLIER_NOT_FOUND(5006, "Không tìm thấy nhà cung cấp.", HttpStatus.NOT_FOUND),
    SUPPLIER_HAS_RECEIPTS(5007, "Không thể xóa nhà cung cấp đã có chứng từ nhập hàng. Vui lòng chuyển sang trạng thái Ngừng hợp tác.", HttpStatus.CONFLICT),
    ORDER_STOCK_NOT_DEDUCTED(5008, "Đơn hàng chưa từng bị trừ tồn kho, không thể hoàn tồn.", HttpStatus.BAD_REQUEST),
    STAFF_REQUIRED_FOR_RETURN(5009, "Cần định danh nhân viên hợp lệ khi xác nhận trả hàng.", HttpStatus.BAD_REQUEST),

    // 6xxx: Promotions & Voucher Engine — Lê Tiến Dũng (7 codes)
    VOUCHER_NOT_FOUND(6001, "Mã giảm giá không tồn tại.", HttpStatus.NOT_FOUND),
    VOUCHER_CODE_EXISTED(6002, "Mã giảm giá này đã tồn tại trên hệ thống.", HttpStatus.CONFLICT),
    VOUCHER_INVALID_OR_EXPIRED(6003, "Mã giảm giá không hợp lệ, chưa đến ngày áp dụng hoặc đã hết hạn.", HttpStatus.BAD_REQUEST),
    VOUCHER_MIN_ORDER_NOT_MET(6004, "Giá trị tiền hàng chưa đạt mức tối thiểu để áp dụng mã giảm giá này.", HttpStatus.BAD_REQUEST),
    VOUCHER_QUOTA_EXHAUSTED(6005, "Mã giảm giá đã hết lượt sử dụng.", HttpStatus.CONFLICT),
    VOUCHER_ALREADY_USED_BY_USER(6006, "Bạn đã sử dụng mã giảm giá này trong một đơn hàng trước đó.", HttpStatus.CONFLICT),
    VOUCHER_IN_USE_CANNOT_DELETE(6007, "Không thể xóa mã giảm giá đã có lịch sử áp dụng. Vui lòng tắt trạng thái hoạt động.", HttpStatus.CONFLICT),

    // 7xxx: Shopping Cart & Order Lifecycle — Nguyễn Phú Trọng & Nguyễn Trần Đức Anh (9 codes)
    CART_EMPTY(7001, "Giỏ hàng của bạn đang trống.", HttpStatus.BAD_REQUEST),
    CART_LINE_LIMIT_EXCEEDED(7002, "Giỏ hàng chỉ được chứa tối đa 20 loại sản phẩm khác nhau.", HttpStatus.BAD_REQUEST),
    CART_ITEM_QUANTITY_EXCEEDED(7003, "Số lượng mua tối đa cho mỗi sản phẩm là 10 đơn vị.", HttpStatus.BAD_REQUEST),
    ORDER_NOT_FOUND(7004, "Không tìm thấy đơn hàng.", HttpStatus.NOT_FOUND),
    INVALID_ORDER_STATE_TRANSITION(7005, "Không thể chuyển trạng thái đơn hàng từ trạng thái hiện tại.", HttpStatus.CONFLICT),
    ORDER_NOT_ELIGIBLE_FOR_CANCEL(7006, "Đơn hàng đã bàn giao vận chuyển hoặc không còn đủ điều kiện để hủy.", HttpStatus.CONFLICT),
    ORDER_PAYMENT_OUTCOME_UNVERIFIED(7007, "Giao dịch thanh toán trực tuyến đang chờ đối soát, chưa thể hủy đơn.", HttpStatus.CONFLICT),
    ORDER_STOCK_ALREADY_RESTORED(7008, "Đơn hàng này đã được hoàn tồn kho trước đó hoặc chưa từng trừ tồn kho.", HttpStatus.CONFLICT),
    ORDER_NOT_ELIGIBLE_FOR_REFUND(7009, "Đơn hàng không đủ điều kiện xác nhận hoàn tiền.", HttpStatus.BAD_REQUEST),

    // 8xxx: VNPay Payment Gateway Integration — Trần Huỳnh Giác (4 codes)
    VNPAY_INVALID_SIGNATURE(8001, "Chữ ký xác thực từ cổng thanh toán không hợp lệ.", HttpStatus.BAD_REQUEST),
    VNPAY_AMOUNT_MISMATCH(8002, "Số tiền thanh toán không khớp với giá trị đơn hàng.", HttpStatus.BAD_REQUEST),
    ORDER_NOT_ELIGIBLE_FOR_PAYMENT(8003, "Đơn hàng không hợp lệ hoặc không còn ở trạng thái chờ thanh toán.", HttpStatus.CONFLICT),
    PAYMENT_INITIATION_FAILED(8004, "Không thể khởi tạo phiên thanh toán trực tuyến. Vui lòng thử lại sau.", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus statusCode;
}
