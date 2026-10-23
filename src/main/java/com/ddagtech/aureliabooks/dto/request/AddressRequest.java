package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.*;

/**
 * UC09: owner resolved from the authenticated principal.
 */
public record AddressRequest(
        @NotBlank(message = "Vui lòng nhập người nhận.") @Size(max = 100, message = "Tên người nhận tối đa 100 ký tự.") String recipientName,
        @NotBlank(message = "Vui lòng nhập số điện thoại người nhận.")
        @Pattern(regexp = "0[0-9]{9}", message = "Số điện thoại gồm 10 chữ số, bắt đầu bằng 0.") String phone,
        @NotBlank(message = "Vui lòng chọn tỉnh/thành phố.")
        @Pattern(regexp = "[0-9]{2}", message = "Tỉnh/thành phố không hợp lệ.") String provinceCode,
        @NotBlank(message = "Vui lòng nhập phường/xã/đặc khu.")
        @Size(max = 100, message = "Phường/xã/đặc khu tối đa 100 ký tự.")
        @Pattern(regexp = "[^\\p{Cc}]*", message = "Tên phường/xã/đặc khu không được chứa ký tự điều khiển.") String ward,
        @NotBlank(message = "Vui lòng nhập địa chỉ chi tiết.") @Size(max = 255, message = "Địa chỉ chi tiết tối đa 255 ký tự.") String detailedAddress,
        Boolean isDefault) {
}
