package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** Dữ liệu checkout và bảng tiền trong một DTO; chưa chọn địa chỉ thì phí ship và tổng thanh toán là null. */
public record CheckoutView(
        List<CartItemView> items,
        List<AddressView> addressViews,
        Long selectedAddressId,
        BigDecimal merchandiseSubtotal,
        BigDecimal productDiscount,
        BigDecimal baseShippingFee,
        BigDecimal shippingDiscount,
        BigDecimal shippingFee,
        BigDecimal finalTotalAmount
) {
    /** Cố định danh sách trong bản xác nhận; thay đổi giỏ hoặc sổ địa chỉ sau đó không sửa được danh sách đã lưu. */
    public CheckoutView {
        items = List.copyOf(items);
        addressViews = List.copyOf(addressViews);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean hasAddresses() {
        return !addressViews.isEmpty();
    }
}
