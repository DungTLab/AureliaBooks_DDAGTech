package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC28. Owner: Trần Huỳnh Giác. Sprint 1 scaffold; business implementation pending. */
public interface AdminUserService {
    java.util.List<RoleOption> listAssignableRoles();

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

    default void updateStaffContact(Long adminId, Long targetUserId, StaffContactUpdateRequest request) {
        updateStaffContact(adminId, targetUserId, request, null);
    }

    /**
     * Updates an internal staff member's phone and/or unverified email after identity verification (UC28 GIAC-02).
     */
    void updateStaffContact(Long adminId, Long targetUserId, StaffContactUpdateRequest request, String ipAddress);

    default void revokeEmailVerification(Long adminId, Long targetUserId) {
        revokeEmailVerification(adminId, targetUserId, null);
    }

    /**
     * Revokes email verification status on another internal staff account (UC28 GIAC-02).
     */
    void revokeEmailVerification(Long adminId, Long targetUserId, String ipAddress);
}

