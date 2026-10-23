package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Form DTO for author creation and updates (UC17).
 *
 * @param id        optional author ID (for update)
 * @param name      author display name (mandatory, 2-100 characters)
 * @param biography biographical overview (optional, max 5000 characters)
 */
public record AuthorForm(
        Long id,

        @NotBlank(message = "Tên tác giả không được để trống.")
        @Size(min = 2, max = 100, message = "Tên tác giả phải có độ dài từ 2 đến 100 ký tự.")
        String name,

        @Size(max = 5000, message = "Tiểu sử tác giả không được vượt quá 5000 ký tự.")
        String biography
) {}

