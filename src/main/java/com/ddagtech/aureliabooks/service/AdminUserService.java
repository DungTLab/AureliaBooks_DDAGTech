package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC28. Owner: Trần Huỳnh Giác. Sprint 1 scaffold; business implementation pending. */
public interface AdminUserService {
    Page<UserSummary> list(String roleName, Pageable pageable);
    Long create(Long adminId, UserCreateRequest request);
    void changeRole(Long adminId, Long userId, Long roleId);
    void setActive(Long adminId, Long userId, boolean active);
}
