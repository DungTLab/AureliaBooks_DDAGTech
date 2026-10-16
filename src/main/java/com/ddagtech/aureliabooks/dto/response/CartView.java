package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record CartView(
        List<CartItemView> items,
        BigDecimal merchandiseSubtotal,
        Integer totalQuantity
) {
    public boolean isEmpty() {
       return items.isEmpty();
    }

    public int itemCount() {
      return items.size();
    }
}
