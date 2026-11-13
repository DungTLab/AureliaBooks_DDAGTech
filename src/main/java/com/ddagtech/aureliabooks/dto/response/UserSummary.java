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
 * @param emailVerified whether the recovery/work email has been verified via OTP
 * @param phoneVerified whether the contact phone number has been verified via OTP
 */
public record UserSummary(
        Long id,
        String email,
        String fullName,
        String phone,
        Long roleId,
        String roleName,
        Boolean active,
        LocalDateTime createdAt,
        boolean emailVerified,
        boolean phoneVerified
) {
    public UserSummary(
            Long id,
            String email,
            String fullName,
            String phone,
            Long roleId,
            String roleName,
            Boolean active,
            LocalDateTime createdAt
    ) {
        this(id, email, fullName, phone, roleId, roleName, active, createdAt, false, false);
    }
}
