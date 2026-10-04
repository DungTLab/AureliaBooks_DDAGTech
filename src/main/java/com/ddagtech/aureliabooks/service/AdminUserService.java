package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC28. Owner: Trần Huỳnh Giác. Sprint 1 scaffold; business implementation pending. */
public interface AdminUserService {
    Page<UserSummary> list(String roleName, Pageable pageable);

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
}

