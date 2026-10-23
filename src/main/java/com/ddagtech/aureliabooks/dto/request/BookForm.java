package com.ddagtech.aureliabooks.dto.request;

import java.math.BigDecimal;
import java.util.Set;
import com.ddagtech.aureliabooks.entity.Book;
import jakarta.validation.constraints.*;

/**
 * Form DTO for Book creation and updates (UC15).
 * Note: stock quantity is strictly excluded per business invariant (updated only via receipts).
 */
public record BookForm(
        Long id,

        @NotBlank(message = "Mã vạch không được để trống.")
        @Size(max = 50, message = "Mã vạch không vượt quá 50 ký tự.")
        @Pattern(regexp = "^\\s*[0-9]{8,14}\\s*$", message = "Mã vạch phải là chuỗi số từ 8 đến 14 chữ số (chuẩn EAN/UPC).")
        String barcode,

        @NotBlank(message = "Tựa sách không được để trống.")
        @Size(max = 255, message = "Tựa sách không vượt quá 255 ký tự.")
        String title,

        @NotNull(message = "Vui lòng chọn danh mục sách.")
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

        @NotBlank(message = "Mã ISBN không được để trống.")
        @Size(max = 20, message = "Mã ISBN không vượt quá 20 ký tự.")
        @Pattern(regexp = "^\\s*(?=(?:(?:[-]*\\d){9}[-]*[0-9Xx]|(?:[-]*\\d){13})$)[0-9Xx-]+\\s*$", message = "Mã ISBN phải đúng định dạng chuẩn ISBN-10 hoặc ISBN-13.")
        String isbn,

        @NotNull(message = "Vui lòng chọn nhà xuất bản.")
        Long publisherId,

        @NotEmpty(message = "Vui lòng chọn ít nhất một tác giả cho sách.")
        Set<Long> authorIds,

        @Size(max = 150, message = "Tên bộ sách không vượt quá 150 ký tự.")
        String seriesName,

        @Min(value = 1, message = "Số tập phải lớn hơn hoặc bằng 1.")
        Integer volumeNumber,

        Boolean isTextbook,

        @Min(value = 1000, message = "Năm xuất bản không hợp lệ.")
        Integer publicationYear,

        @Size(max = 50, message = "Tái bản không vượt quá 50 ký tự.")
        String edition,

        @Min(value = 1, message = "Số trang phải lớn hơn hoặc bằng 1.")
        Integer pageCount,

        @Size(max = 50, message = "Ngôn ngữ không vượt quá 50 ký tự.")
        String language,

        Book.CoverType coverType) {

    public BookForm {
        if (barcode != null) barcode = barcode.strip();
        if (title != null) title = title.strip();
        if (isbn != null) isbn = isbn.strip();
    }
}

