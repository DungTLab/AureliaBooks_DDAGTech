package com.ddagtech.aureliabooks.dto.request;

import java.time.LocalDate;

/** UC05: TODO validation and server-assigned Customer role. Owner: Lê Tiến Dũng. Sprint 1 scaffold; business implementation pending. */
public record RegisterRequest(
        String email,
        String password,
        String fullName,
        String phone,
        LocalDate dob) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
