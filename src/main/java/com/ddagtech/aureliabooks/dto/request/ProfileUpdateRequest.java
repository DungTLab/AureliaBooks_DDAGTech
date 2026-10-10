package com.ddagtech.aureliabooks.dto.request;

import java.time.LocalDate;
import com.ddagtech.aureliabooks.entity.User;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;

/** UC08: existing DOB/phone are immutable; Google accounts may complete missing values once. */
public record ProfileUpdateRequest(
        @NotBlank(message = "Vui lòng nhập họ tên.")
        @Size(min = 2, max = 100, message = "Họ tên từ 2 đến 100 ký tự.")
        @Pattern(regexp = "[\\p{L}\\p{M} ]+", message = "Họ tên chỉ gồm chữ và khoảng trắng.") String fullName,
        @Pattern(regexp = "0[0-9]{9}", message = "Số điện thoại gồm 10 chữ số và bắt đầu bằng 0.") String phone,
        @Past(message = "Ngày sinh phải là ngày trong quá khứ.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
        @NotNull(message = "Vui lòng chọn giới tính.") User.Gender gender) {
    public ProfileUpdateRequest {
        fullName = fullName == null ? null : fullName.strip();
        phone = phone == null || phone.isBlank() ? null : phone.strip();
    }
}
