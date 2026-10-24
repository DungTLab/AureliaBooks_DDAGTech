package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for Hierarchical Category creation and modification (UC20 - SCR-M03d).
 */
public record CategoryForm(
        @NotBlank(message = "Tên danh mục không được để trống")
        @Size(max = 100, message = "Tên danh mục không được vượt quá 100 ký tự")
        String name,

        Long parentId,

        @Size(max = 255, message = "Mô tả danh mục không được vượt quá 255 ký tự")
        String description,

        Boolean active
) {}
