package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.entity.Voucher;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

public record VoucherForm(
        @NotBlank(message = "Vui lòng nhập mã giảm giá.")
        @Pattern(regexp = "[A-Z0-9]{1,30}", message = "Mã gồm tối đa 30 chữ cái không dấu hoặc chữ số.") String code,
        @NotBlank(message = "Vui lòng nhập mô tả.")
        @Size(max = 255, message = "Mô tả tối đa 255 ký tự.") String description,
        @NotNull(message = "Vui lòng chọn phạm vi giảm giá.") Voucher.Scope scope,
        @NotNull(message = "Vui lòng chọn loại giảm giá.") Voucher.Type type,
        @DecimalMin(value = "0.01", message = "Phần trăm giảm phải lớn hơn 0.")
        @DecimalMax(value = "100", message = "Phần trăm giảm không quá 100%.")
        @Digits(integer = 3, fraction = 2, message = "Phần trăm có tối đa 2 chữ số thập phân.") BigDecimal discountPercentage,
        @DecimalMin(value = "0.01", message = "Số tiền giảm phải lớn hơn 0.")
        @Digits(integer = 10, fraction = 2, message = "Số tiền giảm vượt giới hạn cho phép.") BigDecimal discountAmount,
        @DecimalMin(value = "0.01", message = "Mức giảm tối đa phải lớn hơn 0.")
        @Digits(integer = 10, fraction = 2, message = "Mức giảm tối đa vượt giới hạn cho phép.") BigDecimal maxDiscountAmount,
        @NotNull(message = "Vui lòng nhập giá trị đơn tối thiểu.")
        @DecimalMin(value = "0", message = "Giá trị đơn tối thiểu không được âm.")
        @Digits(integer = 10, fraction = 2, message = "Giá trị đơn tối thiểu vượt giới hạn cho phép.") BigDecimal minOrderValue,
        @NotNull(message = "Vui lòng nhập ngày bắt đầu.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
        @NotNull(message = "Vui lòng nhập ngày kết thúc.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
        @NotNull(message = "Vui lòng nhập số lượt sử dụng.")
        @Min(value = 1, message = "Số lượt sử dụng ban đầu phải lớn hơn 0.") Integer usageLimit) {

    public VoucherForm {
        code = code == null ? null : code.strip().toUpperCase(Locale.ROOT);
        description = description == null ? null : description.strip();
    }

    @AssertTrue(message = "Ngày kết thúc phải sau ngày bắt đầu.")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || endDate.isAfter(startDate);
    }

    @AssertTrue(message = "Chọn đúng giá trị giảm theo loại; giảm phần trăm phải có mức giảm tối đa.")
    public boolean isDiscountConfigurationValid() {
        if (type == null) {
            return true;
        }
        return type == Voucher.Type.PERCENTAGE
                ? discountPercentage != null && discountAmount == null && maxDiscountAmount != null
                : discountAmount != null && discountPercentage == null;
    }
}
