package com.ddagtech.aureliabooks.dto.request;

import java.util.List;

/** UC22/23: status, creator and total are resolved by the service. Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
public record GoodsReceiptForm(
        Long supplierId,
        String note,
        List<GoodsReceiptItemForm> items) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
