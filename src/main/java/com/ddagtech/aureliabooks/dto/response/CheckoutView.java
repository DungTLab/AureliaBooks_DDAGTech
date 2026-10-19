package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;
import java.util.List;

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
