package com.ddagtech.aureliabooks.service.impl;

/** Transactional mail content shared by real email, staff reset, and the phone demo transport. */
public final class OtpMailContent {

    public static final String SUBJECT = "AureliaBook - Mã xác thực của bạn";
    public static final String SMS_DEMO_SUBJECT = "[SMS DEMO] " + SUBJECT;
    public static final String STAFF_PASSWORD_RESET_SUBJECT = "[AureliaBook] Cấp lại mật khẩu tài khoản nội bộ";
    public static final String STAFF_ACCOUNT_CREATED_SUBJECT = "[AureliaBook] Thông tin tài khoản nội bộ và mật khẩu khởi tạo";

    private OtpMailContent() {}

    public static String body(String code) {
        return """
        Xin chào,

        AureliaBook nhận được yêu cầu xác thực cho tài khoản của bạn.

        Mã xác thực: %s

        Mã có hiệu lực trong 5 phút và chỉ sử dụng một lần.
        Vui lòng nhập mã tại trang xác thực nơi bạn vừa gửi yêu cầu.

        Không chia sẻ mã này với bất kỳ ai, kể cả người tự nhận là nhân viên AureliaBook.
        Nếu bạn không thực hiện yêu cầu này, hãy bỏ qua email. Không cung cấp mã cho người khác.

        Trân trọng,
        Đội ngũ AureliaBook
        Email được gửi tự động. Vui lòng không trả lời email này.
        """.formatted(code);
    }

    public static String smsDemoBody(String code, String recipient) {
        String maskedPhone = (recipient != null && recipient.length() >= 4)
                ? "******" + recipient.substring(recipient.length() - 4)
                : "******";
        return body(code)
                + "\n[SMS GATEWAY SANDBOX - CHUYỂN TIẾP TỪ SĐT " + maskedPhone + "]"
                + "\nThông tin bản demo: yêu cầu cho số " + maskedPhone + ". Không xác minh quyền sở hữu SĐT thật.\n";
    }

    public static String staffPasswordResetBody(String fullName, String temporaryPassword) {
        return """
        Xin chào %s,

        Quản trị viên đã cấp lại mật khẩu tạm thời cho tài khoản nội bộ của bạn.
        Mật khẩu tạm thời: %s

        Vui lòng đăng nhập tại cổng nội bộ (/internal/login) và đổi sang mật khẩu mới ngay lập tức.

        Trân trọng,
        AureliaBook Security Team
        """.formatted(fullName != null ? fullName : "bạn", temporaryPassword);
    }

    public static String staffAccountCreatedBody(String fullName, String temporaryPassword) {
        return """
        Xin chào %s,

        Tài khoản nội bộ của bạn tại AureliaBook đã được khởi tạo thành công bởi Quản trị viên.
        Mật khẩu khởi tạo: %s

        Vui lòng đăng nhập tại cổng nội bộ (/internal/login) và đổi sang mật khẩu mới ngay trong lần truy cập đầu tiên để đảm bảo an toàn thông tin.

        Trân trọng,
        AureliaBook Security Team
        """.formatted(fullName != null ? fullName : "bạn", temporaryPassword);
    }
}
