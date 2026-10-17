package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.ContactSettingsView;
import com.ddagtech.aureliabooks.entity.User;
import java.time.LocalDate;

/** Application contract for AccountSecurityService; implementation lives in service.impl. */
public interface AccountSecurityService {

    record Draft(String phone, String fullName, LocalDate dob, User.Gender gender, String passwordHash) {

        @Override
        public String toString() {
            return "Draft[redacted]";
        }
    }

    ContactSettingsView contactSettings(Long id);

    User customer(Long id);

    void reauthenticate(Long id, String password, boolean googleProof);

    void register(Draft draft, String ip);

    void reset(Long id, String oldHash, String channel, String recipient, String newHash, String ip);

    User linkGoogle(Long id, String sub, String mail, boolean verified, String ip);
}
