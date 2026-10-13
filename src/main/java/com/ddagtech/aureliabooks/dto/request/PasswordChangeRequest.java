package com.ddagtech.aureliabooks.dto.request;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;


/** UC08: plaintext is input only; never included in diagnostic output. */
public record PasswordChangeRequest(
        @NotBlank(message = "Vui lòng nhập mật khẩu hiện tại.") String currentPassword,
        @NotBlank(message = "Vui lòng nhập mật khẩu mới.")
        @Pattern(regexp = "(?s)^(?=.*\\p{Lu})(?=.*[0-9])(?=.*[^\\p{L}\\p{N}\\s]).{8,}$",
            message = "Mật khẩu từ 8 ký tự, có chữ hoa, số và ký tự đặc biệt.") String newPassword,
        @NotBlank(message = "Vui lòng xác nhận mật khẩu mới.") String confirmPassword) {
    @AssertTrue(message = "Xác nhận mật khẩu không khớp.")
    public boolean isMatching() { return java.util.Objects.equals(newPassword, confirmPassword); }
    @AssertTrue(message = "Mật khẩu tối đa 72 byte UTF-8.")
    public boolean isWithinBcryptLimit() {
        return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    @Override public String toString() { return "PasswordChangeRequest[REDACTED]"; }
}
