package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class StaffIdentity {
    public Long resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CustomUserDetails staff) || staff.getId() == null)
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        return staff.getId();
    }
}
