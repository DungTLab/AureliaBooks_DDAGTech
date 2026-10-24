package com.ddagtech.aureliabooks.service.impl;

/** Transactional mail content shared by real email and the phone demo transport. */
final class OtpMailContent {

    static final String SUBJECT = "AureliaBook - Mã xác thực của bạn";

    private OtpMailContent() {}

    static String body(String code) {
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
}
