package com.ddagtech.aureliabooks.dto.request;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import jakarta.validation.constraints.*;
import com.ddagtech.aureliabooks.entity.User;

/**
 * Request DTO for customer self-registration (UC05 / FE-1).
 * Enforces business rules: Vietnamese 10-digit phone identity (BR-07-01),
 * credential complexity with BCrypt byte limit (FE-1), and optional past date of birth (BR-07-02).
 */
public record RegisterRequest(
        @Email(message = "Email không hợp lệ.")
        @Size(max = 100, message = "Email tối đa 100 ký tự.") String email,
        @NotBlank(message = "Vui lòng nhập mật khẩu.")
        @Pattern(regexp = "(?s)^(?=.*\\p{Lu})(?=.*[0-9])(?=.*[^\\p{L}\\p{N}\\s]).{8,}$",
                message = "Mật khẩu từ 8 ký tự, có chữ hoa, số và ký tự đặc biệt.") String password,
        @NotBlank(message = "Vui lòng nhập họ tên.")
        @Size(min = 2, max = 100, message = "Họ tên từ 2 đến 100 ký tự.")
        @Pattern(regexp = "[\\p{L}\\p{M} ]+", message = "Họ tên chỉ gồm chữ cái và khoảng trắng.") String fullName,
        @NotBlank(message = "Vui lòng nhập số điện thoại.")
        @Pattern(regexp = "0[0-9]{9}", message = "Số điện thoại gồm 10 chữ số, bắt đầu bằng 0.") String phone,
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        @Past(message = "Ngày sinh phải là ngày trong quá khứ.") LocalDate dob,
        @NotBlank(message = "Vui lòng xác nhận mật khẩu.") String confirmPassword,
        User.Gender gender,
        @AssertTrue(message = "Vui lòng đồng ý với điều khoản sử dụng.") boolean agreeTerms) {
    public RegisterRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        fullName = fullName == null ? null : fullName.strip();
        if (phone != null) {
            String cleaned = phone.strip().replace(" ", "").replace("-", "");
            if (cleaned.startsWith("+84") && cleaned.length() == 12) {
                cleaned = "0" + cleaned.substring(3);
            } else if (cleaned.startsWith("84") && cleaned.length() == 11) {
                cleaned = "0" + cleaned.substring(2);
            }
            phone = cleaned;
        }
        gender = gender == null ? User.Gender.OTHER : gender;
    }

    @AssertTrue(message = "Mật khẩu xác nhận chưa khớp.")
    public boolean isPasswordsMatching() {
        return password == null || password.equals(confirmPassword);
    }

    @AssertTrue(message = "Mật khẩu tối đa 72 byte UTF-8 để bảo đảm mã hóa BCrypt.")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }


    @Override
    public String toString() { return "RegisterRequest[credentials redacted]"; }
}
