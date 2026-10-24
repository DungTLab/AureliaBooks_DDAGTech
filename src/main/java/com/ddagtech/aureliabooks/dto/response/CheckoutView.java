package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** Combine checkout details and totals in one DTO; shipping fees and the final total remain null until an address is selected. */
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
    /** Copy the review lists so later cart or address-book changes cannot mutate the saved lists. */
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
