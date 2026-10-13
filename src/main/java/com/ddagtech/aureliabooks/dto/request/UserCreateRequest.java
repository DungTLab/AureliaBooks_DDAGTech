package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Request DTO for provisioning internal staff accounts (UC28 / FE-1).
 * Enforces field validations including email structure, minimum password length (FE-1),
 * and Vietnamese 10-digit mobile phone number format (BR-07-01).
 *
 * @param email user login and contact email address
 * @param password initial plaintext password to be encrypted via BCrypt
 * @param fullName user full display name
 * @param phone mobile phone number following Vietnamese telecom format (BR-07-01)
 * @param roleId persistent role identifier to be assigned
 */
public record UserCreateRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Định dạng email không hợp lệ")
        @Size(max = 100, message = "Email tối đa 100 ký tự")
        String email,

        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(min = 8, max = 72, message = "Mật khẩu phải từ 8 đến 72 ký tự")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#^()_+\\-=\\[\\]{};':\"\\\\|,.<>/]).{8,72}$",
                message = "Mật khẩu phải chứa ít nhất 1 chữ hoa, 1 chữ thường, 1 chữ số và 1 ký tự đặc biệt"
        )
        String password,

        @NotBlank(message = "Họ và tên không được để trống")
        @Size(max = 100, message = "Họ và tên tối đa 100 ký tự")
        String fullName,

        @NotBlank(message = "Số điện thoại không được để trống")
        @Pattern(regexp = "^(0|\\+84)[35789][0-9]{8}$", message = "Số điện thoại không đúng định dạng Việt Nam (10 chữ số)")
        String phone,

        @NotNull(message = "Vui lòng chọn vai trò nhân viên")
        Long roleId
) {
    public UserCreateRequest {
        email = email != null ? email.strip().toLowerCase(Locale.ROOT) : null;
        fullName = fullName != null ? fullName.strip() : null;
        if (phone != null) {
            String cleaned = phone.strip().replace(" ", "").replace("-", "");
            if (cleaned.startsWith("+84") && cleaned.length() == 12) {
                cleaned = "0" + cleaned.substring(3);
            } else if (cleaned.startsWith("84") && cleaned.length() == 11) {
                cleaned = "0" + cleaned.substring(2);
            }
            phone = cleaned;
        }
    }

    /**
     * Validates that the password does not exceed the 72-byte limit of the BCrypt algorithm in UTF-8.
     *
     * @return true if password is null or byte length <= 72, false otherwise
     */
    @AssertTrue(message = "Mật khẩu không được vượt quá 72 byte theo chuẩn mã hóa BCrypt")
    @SuppressWarnings("unused")
    public boolean isPasswordByteLengthValid() {
        if (password == null) {
            return true;
        }
        return password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public String toString() {
        return "UserCreateRequest[email=" + email + ", fullName=" + fullName + ", phone=" + phone + ", roleId=" + roleId + ", password=PROTECTED]";
    }
}

