package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.dto.response.UserSummary;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserMapperTest {

    private final AdminUserMapper mapper = new AdminUserMapper();

    @Test
    @DisplayName("toInternalStaffEntity must leave emailVerified and phoneVerified false even when email and phone are provided")
    void toInternalStaffEntity_LeavesContactFlagsUnverified() {
        UserCreateRequest req = new UserCreateRequest(
                "  Staff.New@AureliaBook.VN ",
                "Nguyen Van Nhan Vien",
                "+84901234567",
                2L
        );
        Role managerRole = Role.builder().id(2L).roleName("ROLE_MANAGER").build();

        User entity = mapper.toInternalStaffEntity(req, "$2a$12$encodedHash", managerRole);

        assertThat(entity.getEmail()).isEqualTo("staff.new@aureliabook.vn");
        assertThat(entity.getPhone()).isEqualTo("0901234567");
        assertThat(entity.getFullName()).isEqualTo("Nguyen Van Nhan Vien");
        assertThat(entity.getRole()).isEqualTo(managerRole);
        assertThat(entity.getAuthProvider()).isEqualTo(User.AuthProvider.LOCAL);
        assertThat(entity.getIsActive()).isTrue();
        assertThat(entity.isEmailVerified()).isFalse();
        assertThat(entity.isPhoneVerified()).isFalse();
    }

    @Test
    @DisplayName("toSummary maps verified contact flags from entity and 8-arg constructor defaults both to false")
    void toSummary_MapsVerificationFlags() {
        Role staffRole = Role.builder().id(3L).roleName("ROLE_SALE_STAFF").build();
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 13, 9, 0);
        User user = User.builder()
                .id(42L)
                .email("staff42@aureliabook.vn")
                .fullName("Le Thi Staff")
                .phone("0912345678")
                .role(staffRole)
                .isActive(true)
                .emailVerified(true)
                .phoneVerified(false)
                .build();
        user.setCreatedAt(createdAt);

        UserSummary summary = AdminUserMapper.toSummary(user);

        assertThat(summary.id()).isEqualTo(42L);
        assertThat(summary.roleName()).isEqualTo("ROLE_SALE_STAFF");
        assertThat(summary.createdAt()).isEqualTo(createdAt);
        assertThat(summary.emailVerified()).isTrue();
        assertThat(summary.phoneVerified()).isFalse();

        UserSummary legacyCompat = new UserSummary(10L, "a@b.vn", "Name", "0901112233", 2L, "ROLE_MANAGER", true, createdAt);
        assertThat(legacyCompat.emailVerified()).isFalse();
        assertThat(legacyCompat.phoneVerified()).isFalse();
    }
}
