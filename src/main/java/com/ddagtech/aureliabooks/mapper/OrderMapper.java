package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.dto.response.OrderConfirmationView;
import com.ddagtech.aureliabooks.entity.CartItem;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.OrderItem;
import com.ddagtech.aureliabooks.entity.ShippingAddress;
import com.ddagtech.aureliabooks.entity.User;
import org.springframework.stereotype.Component;

import java.util.List;
import java.math.BigDecimal;

/** Chỉ sao chép dữ liệu tại thời điểm đặt hàng; service quyết định tính hợp lệ, trạng thái và transaction. */
@Component
public class OrderMapper {

    /** Dựng phiếu xác nhận bằng tên/giá/địa chỉ snapshot; không tính lại theo giá bán hiện tại. */
    public OrderConfirmationView toConfirmation(Order order) {
        List<OrderConfirmationView.Item> items = order.getItems().stream()
                .map(item -> new OrderConfirmationView.Item(item.getProduct().getId(),
                        item.getProductNameSnapshot(), item.getQuantity(), item.getUnitPriceSnapshot(),
                        item.getUnitPriceSnapshot().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList();
        BigDecimal subtotal = items.stream().map(OrderConfirmationView.Item::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderConfirmationView(order.getOrderCode(), order.getCreatedAt(),
                order.getShippingRecipientName(), order.getShippingPhone(), order.getShippingFullAddress(),
                order.getOrderStatus(), order.getPaymentMethod(), order.getPaymentStatus(), items,
                subtotal, subtotal.add(order.getShippingFee()).subtract(order.getFinalTotalAmount())
                        .max(BigDecimal.ZERO), order.getShippingFee(), order.getFinalTotalAmount());
    }

    /** Tạo order/items trong bộ nhớ từ dữ liệu service đã kiểm tra; mapper không tự lưu hoặc trừ kho. */
    public Order toNewOrder(User owner, ShippingAddress address, List<CartItem> items,
                            CheckoutRequest request, CheckoutView checkout,
                            Order.OrderStatus initialStatus) {
        Order order = Order.builder()
                .orderCode(request.getCheckoutToken())
                .user(owner)
                .shippingAddress(address)
                .shippingRecipientName(address.getRecipientName())
                // SĐT liên hệ giao hàng lấy từ địa chỉ, không yêu cầu SĐT tài khoản đã xác minh.
                .shippingPhone(address.getPhone())
                .shippingFullAddress(fullAddress(address))
                .shippingFee(checkout.shippingFee())
                .finalTotalAmount(checkout.finalTotalAmount())
                .paymentMethod(request.getPaymentMethod())
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .orderStatus(initialStatus)
                .build();
        for (CartItem item : items) {
            order.addItem(OrderItem.builder()
                    .product(item.getProduct())
                    .quantity(item.getQuantity())
                    .productNameSnapshot(item.getProduct().getTitle())
                    .unitPriceSnapshot(item.getProduct().getPrice())
                    .build());
        }
        return order;
    }

    /** Ghép cấu trúc địa chỉ hiện tại thành một chuỗi snapshot để đơn giữ nguyên nội dung giao hàng. */
    public String fullAddress(ShippingAddress address) {
        return String.join(", ", address.getDetailedAddress(), address.getWard(),
                address.getDistrict(), address.getProvince());
    }
}
