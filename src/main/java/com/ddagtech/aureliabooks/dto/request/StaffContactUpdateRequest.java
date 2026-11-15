package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Administrative request to update an internal staff member's contact information
 * after out-of-band identity verification (UC28 GIAC-02).
 * Never carries or modifies passwords; verified flags always reset on unverified edits.
 */
public record StaffContactUpdateRequest(
        @Email(message = "Email không đúng định dạng.")
        @Size(max = 100, message = "Email tối đa 100 ký tự.")
        String email,
        @Size(max = 15, message = "Số điện thoại tối đa 15 ký tự.")
        String phone,
        @NotNull(message = "Vui lòng xác nhận đã kiểm tra danh tính nhân sự.")
        Boolean identityVerified,
        @Size(max = 200, message = "Lý do hỗ trợ tối đa 200 ký tự.")
        String reason
) {
}
