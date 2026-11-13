package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.dto.response.RoleOption;
import com.ddagtech.aureliabooks.dto.response.UserSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Service contract managing internal back-office staff accounts and RBAC role assignments (UC28).
 */
public interface AdminUserService {
    List<RoleOption> listAssignableRoles();

    default Page<UserSummary> list(String roleName, Pageable pageable) {
        return list(roleName, null, null, pageable);
    }

    Page<UserSummary> list(String roleName, Boolean active, String keyword, Pageable pageable);

    default Long create(Long adminId, UserCreateRequest request) {
        return create(adminId, request, null);
    }

    Long create(Long adminId, UserCreateRequest request, String ipAddress);

    default void changeRole(Long adminId, Long userId, Long roleId) {
        changeRole(adminId, userId, roleId, null);
    }

    void changeRole(Long adminId, Long userId, Long roleId, String ipAddress);

    default void setActive(Long adminId, Long userId, boolean active) {
        setActive(adminId, userId, active, null);
    }

    void setActive(Long adminId, Long userId, boolean active, String ipAddress);

    default void resetStaffPassword(Long adminId, Long targetUserId) {
        resetStaffPassword(adminId, targetUserId, null);
    }

    void resetStaffPassword(Long adminId, Long targetUserId, String ipAddress);
}

