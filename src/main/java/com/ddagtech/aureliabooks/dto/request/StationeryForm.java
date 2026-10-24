package com.ddagtech.aureliabooks.dto.request;

import java.math.BigDecimal;
import jakarta.validation.constraints.*;

/**
 * Form DTO for Stationery creation and updates (UC16).
 * Note: stock quantity is strictly excluded per business invariant (updated only via receipts).
 */
public record StationeryForm(
        Long id,

        @NotBlank(message = "Mã vạch không được để trống.")
        @Size(max = 50, message = "Mã vạch không vượt quá 50 ký tự.")
        @Pattern(regexp = "^\\s*[0-9]{8,14}\\s*$", message = "Mã vạch phải là chuỗi số từ 8 đến 14 chữ số (chuẩn EAN/UPC).")
        String barcode,

        @NotBlank(message = "Tên sản phẩm không được để trống.")
        @Size(max = 255, message = "Tên sản phẩm không vượt quá 255 ký tự.")
        String title,

        @NotNull(message = "Vui lòng chọn danh mục sản phẩm.")
        Long categoryId,

        @NotNull(message = "Giá bán niêm yết không được để trống.")
        @DecimalMin(value = "0.0", inclusive = true, message = "Giá bán không được âm.")
        BigDecimal price,

        @NotNull(message = "Giá vốn gốc không được để trống.")
        @DecimalMin(value = "0.0", inclusive = true, message = "Giá vốn không được âm.")
        BigDecimal originalCost,

        @Min(value = 0, message = "Khối lượng không được âm.")
        Integer weightGrams,

        @Size(max = 500, message = "Đường dẫn ảnh bìa không vượt quá 500 ký tự.")
        String mainImageUrl,

        @Size(max = 255, message = "Tags không vượt quá 255 ký tự.")
        String tags,

        String description,

        @NotNull(message = "Vui lòng chọn thương hiệu văn phòng phẩm.")
        Long brandId,

        @Size(max = 100, message = "Chất liệu không vượt quá 100 ký tự.")
        String material,

        @Size(max = 50, message = "Màu sắc không vượt quá 50 ký tự.")
        String color,

        @Min(value = 0, message = "Số tháng bảo hành không được âm.")
        Integer warrantyMonths) {

    public StationeryForm {
        if (barcode != null) barcode = barcode.strip();
        if (title != null) title = title.strip();
    }
}
