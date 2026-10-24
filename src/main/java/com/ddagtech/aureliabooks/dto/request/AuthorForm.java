package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for Author creation and update requests (UC17: SCR-M03a).
 */
public record AuthorForm(
        Long id,

        @NotBlank(message = "Tên tác giả không được để trống.")
        @Size(min = 2, max = 100, message = "Tên tác giả phải có độ dài từ 2 đến 100 ký tự.")
        String name,

        @Size(max = 2000, message = "Tiểu sử tác giả không được vượt quá 2000 ký tự.")
        String biography
) {
    public AuthorForm(String name, String biography) {
        this(null, name, biography);
    }
}
