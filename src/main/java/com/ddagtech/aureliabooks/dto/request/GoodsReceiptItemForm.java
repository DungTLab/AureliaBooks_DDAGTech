package com.ddagtech.aureliabooks.dto.request;

import java.math.BigDecimal;

/** UC22/23: received_quantity and unit_cost. Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
public record GoodsReceiptItemForm(
        Long productId,
        Integer receivedQuantity,
        BigDecimal unitCost) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
