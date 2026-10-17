package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.response.ContactSettingsView;
import com.ddagtech.aureliabooks.dto.response.NavbarAccountView;
import com.ddagtech.aureliabooks.dto.response.ProfileView;
import com.ddagtech.aureliabooks.entity.User;

/** Stateless conversion only; callers perform loading, authorization and business validation. */
public final class UserMapper {

    private UserMapper() {
    }

    public static ProfileView toProfile(User user) {
        return new ProfileView(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(),
                user.getDob(), user.getGender().name(), user.getAvatarUrl(), false,
                user.getPasswordHash() != null, user.getDob() == null);
    }

    public static NavbarAccountView toNavbar(User user) {
        return new NavbarAccountView(user.getFullName(), user.getAvatarUrl());
    }

    public static ContactSettingsView toContactSettings(User user) {
        return new ContactSettingsView(user.getPasswordHash() != null,
                user.getEmail() != null && !user.getEmail().isBlank(),
                user.getPhone() != null && !user.getPhone().isBlank());
    }
}
