package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.entity.ShippingAddress;
import jakarta.validation.constraints.*;

/** UC09: owner resolved from the authenticated principal. */
public record AddressRequest(
        @NotBlank(message = "Vui lòng nhập người nhận.") @Size(max = 100, message = "Tên người nhận tối đa 100 ký tự.") String recipientName,
        @NotBlank(message = "Vui lòng nhập số điện thoại người nhận.")
        @Pattern(regexp = "0[0-9]{9}", message = "Số điện thoại gồm 10 chữ số, bắt đầu bằng 0.") String phone,
        @NotNull(message = "Vui lòng chọn vùng kinh tế.") ShippingAddress.EconomicRegion economicRegion,
        @NotBlank(message = "Vui lòng nhập tỉnh/thành phố.") @Size(max = 100, message = "Tỉnh/thành phố tối đa 100 ký tự.") String province,
        @NotBlank(message = "Vui lòng nhập quận/huyện.") @Size(max = 100, message = "Quận/huyện tối đa 100 ký tự.") String district,
        @NotBlank(message = "Vui lòng nhập phường/xã.") @Size(max = 100, message = "Phường/xã tối đa 100 ký tự.") String ward,
        @NotBlank(message = "Vui lòng nhập địa chỉ chi tiết.") @Size(max = 255, message = "Địa chỉ chi tiết tối đa 255 ký tự.") String detailedAddress,
        Boolean isDefault) {
}
