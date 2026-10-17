package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.response.UserSummary;
import com.ddagtech.aureliabooks.entity.User;

public final class AdminUserMapper {
    private AdminUserMapper() {
    }

    public static UserSummary toSummary(User user) {
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole() != null ? user.getRole().getId() : null,
                user.getRole() != null ? user.getRole().getRoleName() : null,
                user.getIsActive(),
                user.getCreatedAt()
        );
    }
}
