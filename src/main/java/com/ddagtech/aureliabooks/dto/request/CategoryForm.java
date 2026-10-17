package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** UC20. Owner: Huỳnh Nhật Duy. TODO reject self/descendant parent cycles. */
public record CategoryForm(
        @NotBlank(message = "Tên danh mục không được để trống")
        @Size(max=100, message = "Tên danh mục không được vượt quá 100 kí tự")
        String name,

        Long parentId,
        @Size(max=255, message = "Mô tả danh mục không được vượt quá 255 kí tự")
        String description,

        Boolean active) {}
