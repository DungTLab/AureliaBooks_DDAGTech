package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.dto.response.ContactSettingsView;
import com.ddagtech.aureliabooks.dto.response.NavbarAccountView;
import com.ddagtech.aureliabooks.dto.response.ProfileView;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Stateless conversion only; callers perform loading, authorization and business validation.
 */
@Component
public class UserMapper {

    public static ProfileView toProfile(User user) {
        if (user == null) {
            return null;
        }
        String genderStr = user.getGender() != null ? user.getGender().name() : null;
        boolean canCompletePhone = user.getAuthProvider() == User.AuthProvider.GOOGLE
                && (user.getPhone() == null || user.getPhone().isBlank());
        return new ProfileView(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getDob(),
                genderStr,
                user.getAvatarUrl(),
                canCompletePhone,
                user.getPasswordHash() != null,
                user.getDob() == null || !com.ddagtech.aureliabooks.validation.BirthDateValidator
                        .isValidDate(user.getDob(), java.time.LocalDate.now())
        );
    }

    public static NavbarAccountView toNavbar(User user) {
        if (user == null) {
            return null;
        }
        return new NavbarAccountView(user.getFullName(), user.getAvatarUrl());
    }

    public static ContactSettingsView toContactSettings(User user) {
        if (user == null) {
            return null;
        }
        return new ContactSettingsView(
                user.getPasswordHash() != null,
                user.getEmail() != null && !user.getEmail().isBlank(),
                user.getPhone() != null && !user.getPhone().isBlank()
        );
    }

    public User toLocalCustomerEntity(RegisterRequest req, String encodedPassword, Role customerRole) {
        if (req == null) {
            return null;
        }
        String cleanEmail = (req.email() != null && !req.email().isBlank())
                ? req.email().strip().toLowerCase(Locale.ROOT)
                : null;
        return User.builder()
                .email(cleanEmail)
                .phone(req.phone() != null ? req.phone().strip() : null)
                .fullName(req.fullName() != null ? req.fullName().strip() : null)
                .dob(req.dob())
                .gender(req.gender())
                .passwordHash(encodedPassword)
                .authProvider(User.AuthProvider.LOCAL)
                .role(customerRole)
                .isActive(true)
                .build();
    }

    public ProfileView toProfileView(User user) {
        return toProfile(user);
    }

    public NavbarAccountView toNavbarAccountView(User user) {
        return toNavbar(user);
    }
}
