package com.ddagtech.aureliabooks.dto.request;


/** UC28: single roleId; TODO allowed staff roles. Owner: Trần Huỳnh Giác. Sprint 1 scaffold; business implementation pending. */
public record UserCreateRequest(
        String email,
        String password,
        String fullName,
        String phone,
        Long roleId) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
