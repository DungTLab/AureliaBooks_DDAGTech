package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.service.AccountSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Session-bound reauthentication; a different Google identity must never replace the signed-in account. */
@Component
@RequiredArgsConstructor
public class GoogleAccountAction {

    private final AccountSecurityService security;

    public record Intent(Long owner, String purpose, Instant expires) {}

    public static void begin(HttpSession session, Long owner, String purpose) {
        session.removeAttribute("CONTACT_REAUTH_OWNER");
        session.removeAttribute("CONTACT_REAUTH_UNTIL");
        session.removeAttribute("GOOGLE_REAUTH_OWNER");
        session.removeAttribute("GOOGLE_REAUTH_UNTIL");
        session.removeAttribute("GOOGLE_REAUTH_ORIGINAL_AUTH");
        session.setAttribute("GOOGLE_ACCOUNT_ACTION", new Intent(owner, purpose, Instant.now().plusSeconds(300)));
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            session.setAttribute("GOOGLE_REAUTH_ORIGINAL_AUTH", authentication);
        }
    }

    public User resolve(OAuth2User claims) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        var session = attributes.getRequest().getSession(false);
        if (session == null || !(session.getAttribute("GOOGLE_ACCOUNT_ACTION") instanceof Intent intent)) {
            return null;
        }
        session.removeAttribute("GOOGLE_ACCOUNT_ACTION");
        if (!Instant.now().isBefore(intent.expires())) {
            throw new IllegalArgumentException("Yêu cầu Google đã hết hạn.");
        }
        if (!"REAUTH".equals(intent.purpose())) {
            throw new IllegalArgumentException("Thao tác Google không còn được hỗ trợ.");
        }
        if (!Boolean.TRUE.equals(claims.getAttribute("email_verified"))) {
            throw new IllegalArgumentException("Email Google chưa xác minh.");
        }
        String subject = claims.getAttribute("sub");
        var owner = security.customer(intent.owner());
        if (subject == null || !subject.equals(owner.getProviderId())) {
            throw new IllegalArgumentException("Hãy chọn đúng Google đã liên kết với tài khoản này.");
        }
        session.setAttribute("CONTACT_REAUTH_OWNER", owner.getId());
        session.setAttribute("CONTACT_REAUTH_TYPE", session.getAttribute("GOOGLE_REAUTH_TYPE"));
        session.setAttribute("CONTACT_REAUTH_UNTIL", Instant.now().plusSeconds(300));
        session.setAttribute("GOOGLE_REAUTH_OWNER", owner.getId());
        session.setAttribute("GOOGLE_REAUTH_UNTIL", Instant.now().plusSeconds(300));
        session.setAttribute("GOOGLE_PROFILE_ACTION_DONE", "REAUTH");
        return owner;
    }

    public static String returnPath(HttpSession session) {
        return (
            "/profile/contact-change?type=" +
            ("PHONE".equals(session.getAttribute("GOOGLE_REAUTH_TYPE")) ? "PHONE" : "EMAIL")
        );
    }

    /** The OAuth filter clears its context on failure; restore only the server-stored pre-reauthentication login. */
    public static boolean restoreAfterFailure(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (
            session == null || !(session.getAttribute("GOOGLE_REAUTH_ORIGINAL_AUTH") instanceof Authentication original)
        ) {
            return false;
        }
        session.removeAttribute("GOOGLE_REAUTH_ORIGINAL_AUTH");
        session.removeAttribute("GOOGLE_ACCOUNT_ACTION");
        session.removeAttribute("GOOGLE_PROFILE_ACTION_DONE");
        session.removeAttribute("GOOGLE_REAUTH_OWNER");
        session.removeAttribute("GOOGLE_REAUTH_UNTIL");
        session.removeAttribute("CONTACT_REAUTH_OWNER");
        session.removeAttribute("CONTACT_REAUTH_UNTIL");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(original);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        return true;
    }
}
