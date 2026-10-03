package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for provisioning internal staff accounts (UC28).
 * Enforces field validations including email structure, minimum password length,
 * and Vietnamese mobile phone number format.
 *
 * @param email user login and contact email address
 * @param password initial plaintext password to be encrypted via BCrypt
 * @param fullName user full display name
 * @param phone mobile phone number following Vietnamese telecom format
 * @param roleId persistent role identifier to be assigned
 */
public record UserCreateRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Định dạng email không hợp lệ")
        @Size(max = 100, message = "Email tối đa 100 ký tự")
        String email,

        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(min = 8, message = "Mật khẩu phải chứa ít nhất 8 ký tự")
        String password,

        @NotBlank(message = "Họ và tên không được để trống")
        @Size(max = 100, message = "Họ và tên tối đa 100 ký tự")
        String fullName,

        @NotBlank(message = "Số điện thoại không được để trống")
        @Pattern(regexp = "^(0|\\+84)[3|5|7|8|9][0-9]{8}$", message = "Số điện thoại không đúng định dạng Việt Nam")
        String phone,

        @NotNull(message = "Vui lòng chọn vai trò nhân viên")
        Long roleId
) {
}

