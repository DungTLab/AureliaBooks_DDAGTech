package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

/** Deployment input only; never expose credentials through diagnostics. */
public record BootstrapAdminRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank
        @Pattern(regexp = "(?s)^(?=.*\\p{Lu})(?=.*[0-9])(?=.*[^\\p{L}\\p{N}\\s]).{8,}$") String password,
        @Pattern(regexp = "0[0-9]{9}") String phone) {

    @AssertTrue
    public boolean isWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() {
        return "BootstrapAdminRequest[REDACTED]";
    }
}
