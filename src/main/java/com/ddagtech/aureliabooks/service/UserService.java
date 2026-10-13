package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC08 profile/password contract; registration delegates to the existing UC05 service. */
public interface UserService {
    Long register(RegisterRequest request);
    ProfileView viewProfile(Long authenticatedUserId);
    void updateProfile(Long authenticatedUserId, ProfileUpdateRequest request);
    void changePassword(Long authenticatedUserId, PasswordChangeRequest request);
    void updateAvatar(Long authenticatedUserId, org.springframework.web.multipart.MultipartFile file);
}
