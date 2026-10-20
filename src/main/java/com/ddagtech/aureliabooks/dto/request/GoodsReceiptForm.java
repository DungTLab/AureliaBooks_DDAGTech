package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** UC22/23: status, creator and total are resolved by the service. Owner: Nguyễn Trần Đức Anh. */
public record GoodsReceiptForm(
        @NotNull(message = "Vui lòng chọn nhà cung cấp.")
        Long supplierId,

        @Size(max = 500, message = "Ghi chú không được vượt quá 500 ký tự.")
        String note,

        @NotEmpty(message = "Phiếu nhập kho phải có ít nhất một sản phẩm.")
        List<@Valid GoodsReceiptItemForm> items
) {
    public GoodsReceiptForm {
        if (items == null) {
            items = List.of();
        }
    }
}
