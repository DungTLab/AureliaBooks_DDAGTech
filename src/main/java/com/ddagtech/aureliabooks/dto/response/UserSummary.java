package com.ddagtech.aureliabooks.dto.response;

import java.time.LocalDateTime;

/**
 * Summary DTO representing an internal user account for administrative listings (UC28).
 *
 * @param id persistent user identifier
 * @param email user login and contact email address
 * @param fullName user full display name
 * @param phone mobile phone number
 * @param roleId persistent role identifier
 * @param roleName assigned Spring Security role authority name (e.g., ROLE_ADMIN)
 * @param active account active status indicator
 * @param createdAt timestamp when the account record was created
 */
public record UserSummary(
        Long id,
        String email,
        String fullName,
        String phone,
        Long roleId,
        String roleName,
        Boolean active,
        LocalDateTime createdAt
) {
}

