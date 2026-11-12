package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request form DTO for creating and editing goods receipts (UC21, UC22, UC23).
 * Header metadata including receipt code, creator, and total amount are computed by the service layer.
 *
 * @param supplierId persistent identifier of the selected supplier
 * @param note optional descriptive notes for this inbound shipment
 * @param items list of receipt line items
 */
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
