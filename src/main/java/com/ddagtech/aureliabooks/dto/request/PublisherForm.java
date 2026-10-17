package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * UC18. Owner: Huỳnh Nhật Duy. SQL has no publisher phone column.
 */
public record PublisherForm(
        @NotBlank(message = "Tên nahf xuất bản không được để trống")
        @Size(max =100, message = "Tên nhà xuất bnar không được vượt quá 150 kí tự")
        String name,
        @Size(max = 255, message = "Địa chỉ không được vượt quá 255 kí tự")
        String address,
        @Email(message = "Địa chỉ email không đúng định dạng chuẩn")
        @Size(max=100, message = "Email không được vượt quá 150 kí tự")
        String email) {
}
