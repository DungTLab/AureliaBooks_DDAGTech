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
    // 9xxx: System & Infrastructure Errors
    UNCATEGORIZED_EXCEPTION(9999, "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(9001, "Dữ liệu yêu cầu không hợp lệ.", HttpStatus.BAD_REQUEST),

    // 1xxx: Authentication & Authorization (Phan he Auth / RBAC)
    UNAUTHENTICATED(1001, "Bạn cần đăng nhập để tiếp tục.", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(1002, "Bạn không có quyền truy cập nội dung này.", HttpStatus.FORBIDDEN),
    USER_EXISTED(1003, "Email hoặc số điện thoại này đã được đăng ký.", HttpStatus.CONFLICT),
    USER_NOT_EXISTED(1004, "Không tìm thấy tài khoản.", HttpStatus.NOT_FOUND),
    INVALID_CREDENTIALS(1005, "Email, số điện thoại hoặc mật khẩu không đúng.", HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(1006, "Tài khoản đã bị khóa hoặc ngừng hoạt động.", HttpStatus.FORBIDDEN),
    PASSWORD_NOT_MATCH(1007, "Mật khẩu hiện tại không đúng.", HttpStatus.BAD_REQUEST),
    DOB_IMMUTABLE(1009, "Không thể thay đổi ngày sinh sau khi đăng ký.", HttpStatus.BAD_REQUEST),

    // 2xxx: Address & Shipping Constraints
    ADDRESS_QUOTA_EXCEEDED(2001, "Bạn chỉ có thể lưu tối đa 5 địa chỉ giao hàng đang hoạt động.", HttpStatus.BAD_REQUEST),
    ADDRESS_NOT_FOUND(2002, "Không tìm thấy địa chỉ giao hàng.", HttpStatus.NOT_FOUND),
    ADDRESS_LOCKED_ACTIVE_ORDER(2003, "Không thể xóa địa chỉ đang được sử dụng cho đơn hàng chưa hoàn tất.", HttpStatus.BAD_REQUEST),

    // 3xxx: Generic & Resource Validation
    RESOURCE_NOT_FOUND(3001, "Không tìm thấy dữ liệu yêu cầu.", HttpStatus.NOT_FOUND),
    INVALID_INPUT_DATA(3002, "Thông tin nhập chưa hợp lệ. Vui lòng kiểm tra lại.", HttpStatus.BAD_REQUEST),

    // 4xxx: Inventory & Stock Constraints (FND-03 / Subsystem 4)
    PRODUCT_NOT_FOUND(4001, "Không tìm thấy sản phẩm trong kho.", HttpStatus.NOT_FOUND),
    INSUFFICIENT_STOCK(4002, "Số lượng tồn kho khả dụng không đủ để thực hiện giao dịch.", HttpStatus.BAD_REQUEST),
    NEGATIVE_STOCK_NOT_ALLOWED(4003, "Giao dịch bị từ chối: Số lượng tồn kho không được phép âm.", HttpStatus.BAD_REQUEST),
    INVALID_QUANTITY_CHANGE(4004, "Biến động số lượng kho phải khác 0.", HttpStatus.BAD_REQUEST),
    STOCK_LEDGER_BALANCE_MISMATCH(4005, "Vi phạm cân bằng thẻ kho: Tồn cuối phải bằng tồn đầu cộng số lượng biến động.", HttpStatus.BAD_REQUEST),
    STOCK_LEDGER_IMMUTABLE(4006, "Thẻ kho là dữ liệu bất biến, không được phép chỉnh sửa hoặc xóa.", HttpStatus.FORBIDDEN);

    private final int code;
    private final String message;
    private final HttpStatus statusCode;
}
