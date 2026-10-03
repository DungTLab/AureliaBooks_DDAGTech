package com.ddagtech.aureliabooks.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Global Error Codes Enumeration as defined in RDS Section 3.2.
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    // 9xxx: System & Infrastructure Errors
    UNCATEGORIZED_EXCEPTION(9999, "Uncategorized system error occurred", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(9001, "Invalid message or configuration key", HttpStatus.BAD_REQUEST),

    // 1xxx: Authentication & Authorization (Phan he Auth / RBAC)
    UNAUTHENTICATED(1001, "Unauthenticated access. Please log in", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(1002, "You do not have permission to access this resource", HttpStatus.FORBIDDEN),
    USER_EXISTED(1003, "User with this phone number or email already exists", HttpStatus.CONFLICT),
    USER_NOT_EXISTED(1004, "User account not found", HttpStatus.NOT_FOUND),
    INVALID_CREDENTIALS(1005, "Incorrect username, email, or password", HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(1006, "User account is locked or deactivated", HttpStatus.FORBIDDEN),
    PASSWORD_NOT_MATCH(1007, "Current password does not match", HttpStatus.BAD_REQUEST),
    AGE_RESTRICTION_VIOLATED(1008, "User age must be at least 13 years old (BR-07-02)", HttpStatus.BAD_REQUEST),
    DOB_IMMUTABLE(1009, "Date of birth cannot be modified after registration (BR-04-01)", HttpStatus.BAD_REQUEST),
    CANNOT_LOCK_SELF(1010, "Không thể tự khóa tài khoản của bản thân (BR-08-01)", HttpStatus.BAD_REQUEST),
    CANNOT_REVOKE_LAST_ADMIN(1011, "Không thể thu hồi quyền Quản trị viên của Admin duy nhất còn lại trong hệ thống", HttpStatus.BAD_REQUEST),
    INVALID_ROLE_ASSIGNMENT(1012, "Chỉ được phép gán các vai trò nội bộ: SALE_STAFF, MANAGER, ADMIN", HttpStatus.BAD_REQUEST),

    // 2xxx: Address & Shipping Constraints
    ADDRESS_QUOTA_EXCEEDED(2001, "Maximum 5 shipping addresses allowed per customer (BR-21)", HttpStatus.BAD_REQUEST),
    ADDRESS_NOT_FOUND(2002, "Shipping address not found", HttpStatus.NOT_FOUND),
    ADDRESS_LOCKED_ACTIVE_ORDER(2003, "Cannot delete or edit address linked to active processing order (BR-21)", HttpStatus.BAD_REQUEST),

    // 3xxx: Generic & Resource Validation
    RESOURCE_NOT_FOUND(3001, "Requested resource not found", HttpStatus.NOT_FOUND),
    INVALID_INPUT_DATA(3002, "Input validation failed", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatus statusCode;
}
