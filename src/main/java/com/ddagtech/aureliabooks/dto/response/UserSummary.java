package com.ddagtech.aureliabooks.dto.response;


public record UserSummary(Long id, String email, String fullName, String roleName, Boolean active) {}
