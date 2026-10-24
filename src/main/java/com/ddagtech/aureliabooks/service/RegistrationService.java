package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.entity.User;

/** Application contract for RegistrationService; implementation lives in service.impl. */
public interface RegistrationService {
    Long registerLocal(RegisterRequest request);

    User registerGoogle(String subject, String email, Boolean verified, String name, String picture);
}
