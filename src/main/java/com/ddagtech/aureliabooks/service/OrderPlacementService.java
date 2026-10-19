package com.ddagtech.aureliabooks.service;
import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.dto.response.OrderConfirmationView;

/** Contract UC11: đọc checkout, tạo đơn nguyên tử và đọc phiếu xác nhận thuộc khách đăng nhập. */
public interface OrderPlacementService {
    /** Chọn địa chỉ được yêu cầu hoặc địa chỉ mặc định, trả bảng tiền xem trước; không giữ tồn kho. */
    CheckoutView getCheckoutView(Long userId, Long shippingAddressId);


    /** Chỉ trả snapshot đơn thuộc userId; không tồn tại hoặc khác chủ sở hữu đều báo không tìm thấy. */
    OrderConfirmationView getOrderConfirmation(Long userId, String orderCode);

    /**
     * Tạo đơn COD/VNPay và trừ kho cùng transaction; gửi lại token đã commit thì trả đơn cũ.
     * userId lấy từ tài khoản đăng nhập, confirmation lấy từ session do server quản lý.
     * Token đồng thời là mã đơn duy nhất. Giá/địa chỉ/số lượng đổi phải yêu cầu khách xác nhận lại.
     */
    String placeOrder(Long userId, CheckoutRequest request, CheckoutConfirmation confirmation);
}
