package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.service.*;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GoogleSignInServiceImpl implements GoogleSignInService {

    private final UserRepository users;
    private final RegistrationService registration;
    private final AccountSecurityService security;
    private final AccountOtpService otp;
    private final LoginAttemptService attempts;

    @Override
    public Outcome resolve(Identity identity, String session, String ip) {
        if (!identity.verified() || identity.subject() == null || identity.subject().isBlank()
                || identity.subject().length() > 100 || identity.email() == null) {
            throw new IllegalArgumentException("Google chưa xác thực thông tin tài khoản.");
        }
        String email = identity.email().strip().toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.length() > 100) {
            throw new IllegalArgumentException("Email Google không hợp lệ.");
        }
        if (users.findByGoogleIdentity(User.AuthProvider.GOOGLE, identity.subject()).isPresent()) {
            return registered(identity, email);
        }
        var existing = users.findByIdentifierWithRoles(email);
        if (existing.isEmpty()) {
            return registered(identity, email);
        }
        User user = existing.get();
        CustomerIdentity.requireCustomer(user);
        if (user.getProviderId() != null && !user.getProviderId().equals(identity.subject())) {
            throw new IllegalArgumentException("Tài khoản đã liên kết với Google khác.");
        }
        String domain = email.substring(email.indexOf('@') + 1);
        boolean authoritative = domain.equals("gmail.com") || identity.hostedDomain() != null
                && !identity.hostedDomain().isBlank() && domain.equalsIgnoreCase(identity.hostedDomain());
        if (authoritative && user.isEmailVerified()) {
            User linked = attempts.authenticate(user.getId(), () -> security.completeGoogleLink(user.getId(),
                    identity.subject(), email, "AUTO", user.getEmail(), ip));
            return new Outcome(linked, null, true);
        }
        return new Outcome(null, otp.prepareGoogleLink(user.getId(), identity.subject(), email, session), false);
    }

    private Outcome registered(Identity identity, String email) {
        return new Outcome(registration.registerGoogle(identity.subject(), email, true, identity.name(), identity.picture()), null, false);
    }
}
