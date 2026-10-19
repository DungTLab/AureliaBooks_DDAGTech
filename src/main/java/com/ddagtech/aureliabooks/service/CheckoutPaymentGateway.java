package com.ddagtech.aureliabooks.service;

/**
 * Điểm tích hợp UC11.1: chỉ đăng ký implementation khi VNPay đã được cấu hình.
 * Adapter đọc đơn đã commit thuộc khách, kiểm tra trạng thái và ký URL bằng số tiền đã lưu.
 * Mở cổng thanh toán không được trừ kho hoặc tự đánh dấu thanh toán thành công.
 */
public interface CheckoutPaymentGateway {
    /** Cho controller biết có thể mở cổng ngay lúc này hay không. */
    boolean isAvailable();

    /** Trả URL HTTPS đã ký cho đơn có sẵn; controller gọi sau transaction tạo đơn. */
    String startPayment(Long authenticatedUserId, String orderCode);
}
