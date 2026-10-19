package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.entity.Order;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Form đặt hàng cuối chỉ gửi lựa chọn và token; giá, số điện thoại nhận hàng và tổng tiền được đọc lại phía server. */
@Getter
@Setter
@NoArgsConstructor
public class CheckoutRequest {
    @NotNull(message = "Vui lòng chọn địa chỉ giao hàng")
    @Positive(message = "Địa chỉ giao hàng không hợp lệ")
    private Long shippingAddressId;

    @NotNull(message = "Vui lòng chọn phương thức thanh toán")
    private Order.PaymentMethod paymentMethod;

    // Giữ điểm tích hợp UC11.2; service hiện từ chối mã khác rỗng khi chưa có nghiệp vụ voucher.
    @Size(max = 30, message = "Mã giảm giá tối đa 30 ký tự")
    private String voucherCode;

    // Token UUID bỏ dấu gạch nối, đồng thời làm order_code để nhận diện request gửi lại.
    @NotBlank(message = "Phiên xác nhận đặt hàng không hợp lệ")
    @Pattern(
            regexp = "[a-f0-9]{32}",
            message = "Mã xác nhận đặt hàng không hợp lệ"
    )
    private String checkoutToken;
}
