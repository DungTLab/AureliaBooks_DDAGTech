package com.ddagtech.aureliabooks.dto.response;

/** Presentation fields only: never expose the user entity or credential hash. */
public record NavbarAccountView(String fullName, String avatarUrl) {}
