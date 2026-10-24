package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for Publisher creation and modification (UC18 - SCR-M03b).
 */
public record PublisherForm(
        @NotBlank(message = "Tên nhà xuất bản không được để trống")
        @Size(max = 150, message = "Tên nhà xuất bản không được vượt quá 150 ký tự")
        String name,

        @Size(max = 255, message = "Địa chỉ không được vượt quá 255 ký tự")
        String address,

        @Email(message = "Địa chỉ email không đúng định dạng chuẩn")
        @Size(max = 100, message = "Email không được vượt quá 100 ký tự")
        String email
) {}
