package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC05/06/07/08. Owner: Lê Tiến Dũng. Sprint 1 scaffold; business implementation pending. */
public interface UserService {
    Long register(RegisterRequest request);
    ProfileView viewProfile(Long authenticatedUserId);
    void updateProfile(Long authenticatedUserId, ProfileUpdateRequest request);
    void changePassword(Long authenticatedUserId, PasswordChangeRequest request);
}
