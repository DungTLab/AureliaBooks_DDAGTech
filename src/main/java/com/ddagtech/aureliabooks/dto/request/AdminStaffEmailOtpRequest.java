package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Administrative request to initiate an OTP verification challenge for an internal
 * staff member's replacement recovery email during lost-channel support (UC28 GIAC-02).
 */
public record AdminStaffEmailOtpRequest(
        @NotBlank(message = "Vui lòng nhập email công việc mới.")
        @Email(message = "Email không đúng định dạng.")
        @Size(max = 100, message = "Email tối đa 100 ký tự.")
        String email,
        @NotNull(message = "Vui lòng xác nhận đã kiểm tra danh tính nhân sự.")
        Boolean identityVerified,
        @Size(max = 200, message = "Lý do hỗ trợ tối đa 200 ký tự.")
        String reason
) {
}
