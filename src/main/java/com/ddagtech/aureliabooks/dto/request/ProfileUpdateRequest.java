package com.ddagtech.aureliabooks.dto.request;

import java.time.LocalDate;
import com.ddagtech.aureliabooks.entity.User;

/** UC08: avatar upload handled separately; never trust client-supplied userId. Owner: Lê Tiến Dũng. Sprint 1 scaffold; business implementation pending. */
public record ProfileUpdateRequest(
        String fullName,
        String phone,
        LocalDate dob,
        User.Gender gender) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
