package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for Stationery Brand creation and modification (UC19 - SCR-M03c).
 */
public record BrandForm(
        @NotBlank(message = "Tên thương hiệu không được để trống")
        @Size(max = 100, message = "Tên thương hiệu không được vượt quá 100 ký tự")
        String name,

        @Size(max = 100, message = "Quốc gia xuất xứ không được vượt quá 100 ký tự")
        String originCountry
) {}
