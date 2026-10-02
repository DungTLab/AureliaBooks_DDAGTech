package com.ddagtech.aureliabooks.dto.response;

import java.time.LocalDate;

public record ProfileView(Long id, String email, String fullName, String phone, LocalDate dob, String gender, String avatarUrl) {}
