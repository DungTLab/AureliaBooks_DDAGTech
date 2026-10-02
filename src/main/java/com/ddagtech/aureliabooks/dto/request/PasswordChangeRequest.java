package com.ddagtech.aureliabooks.dto.request;


/** UC08: TODO password verification. Owner: Lê Tiến Dũng. Sprint 1 scaffold; business implementation pending. */
public record PasswordChangeRequest(
        String currentPassword,
        String newPassword,
        String confirmPassword) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
