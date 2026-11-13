package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.response.AdminStaffEmailChallengeView;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.security.AuthenticatedSessionUpdater;
import com.ddagtech.aureliabooks.security.StaffIdentity;
import com.ddagtech.aureliabooks.service.AccountSecurityService;
import com.ddagtech.aureliabooks.service.StaffContactService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Self-service recovery work email verification controller for authenticated internal staff (UC28 GIAC-02).
 * Enforces password re-authentication within a 5-minute session window before issuing OTP codes.
 */
@Controller
@RequestMapping("/internal/account/email")
@RequiredArgsConstructor
public class InternalAccountEmailController {

    static final String REAUTH_OWNER_ATTR = "INTERNAL_EMAIL_REAUTH_OWNER";
    static final String REAUTH_UNTIL_ATTR = "INTERNAL_EMAIL_REAUTH_UNTIL";
    private static final Duration REAUTH_WINDOW = Duration.ofMinutes(5);
    private static final String REDIRECT_SELF = "redirect:/internal/account/email";

    private final StaffIdentity staffIdentity;
    private final AccountSecurityService accountSecurityService;
    private final StaffContactService staffContactService;
    private final AuthenticatedSessionUpdater sessionUpdater;

    @GetMapping
    public String viewRecoveryEmail(
            Authentication authentication,
            HttpSession session,
            Model model
    ) {
        Long staffId = staffIdentity.resolve(authentication);
        User staff = staffContactService.loadInternalStaff(staffId);
        AdminStaffEmailChallengeView challenge = staffContactService
                .findSelfEmailChallenge(staffId, session.getId())
                .orElse(null);

        model.addAttribute("staff", staff);
        model.addAttribute("reauthenticated", hasValidReauth(session, staffId));
        model.addAttribute("challenge", challenge);
        return "internal/recovery-email";
    }

    @PostMapping("/reauth")
    public String reauthenticate(
            @RequestParam(name = "currentPassword", defaultValue = "") String currentPassword,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long staffId = staffIdentity.resolve(authentication);
        if (currentPassword.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Vui lòng nhập mật khẩu hiện tại.");
            return REDIRECT_SELF;
        }
        try {
            accountSecurityService.reauthenticateStaff(staffId, currentPassword);
            session.setAttribute(REAUTH_OWNER_ATTR, staffId);
            session.setAttribute(REAUTH_UNTIL_ATTR, Instant.now().plus(REAUTH_WINDOW));
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Xác thực thành công. Vui lòng nhận và xác minh mã OTP gửi tới email công việc."
            );
        } catch (AppException ex) {
            clearReauthGrant(session);
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return REDIRECT_SELF;
    }

    @PostMapping("/start")
    public String startVerification(
            @RequestParam(name = "email", defaultValue = "") String email,
            Authentication authentication,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long staffId = staffIdentity.resolve(authentication);
        if (!hasValidReauth(session, staffId)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Vui lòng xác thực lại mật khẩu hiện tại trước khi gửi OTP.");
            return REDIRECT_SELF;
        }
        try {
            AdminStaffEmailChallengeView challenge = staffContactService.startSelfEmailVerification(
                    staffId,
                    session.getId(),
                    email,
                    HttpRequestUtil.getClientIp(request)
            );
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã gửi mã xác thực 6 số tới " + challenge.maskedEmail() + "."
            );
        } catch (AppException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return REDIRECT_SELF;
    }

    @PostMapping("/resend")
    public String resendVerification(
            Authentication authentication,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long staffId = staffIdentity.resolve(authentication);
        try {
            AdminStaffEmailChallengeView challenge = staffContactService.resendSelfEmailVerification(
                    staffId,
                    session.getId(),
                    HttpRequestUtil.getClientIp(request)
            );
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã gửi lại mã xác thực tới " + challenge.maskedEmail() + "."
            );
        } catch (AppException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return REDIRECT_SELF;
    }

    @PostMapping("/verify")
    public String verifyOtp(
            @RequestParam(name = "code", defaultValue = "") String code,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long staffId = staffIdentity.resolve(authentication);
        try {
            User updated = staffContactService.verifySelfEmail(
                    staffId,
                    session.getId(),
                    code,
                    HttpRequestUtil.getClientIp(request)
            );
            clearReauthGrant(session);
            sessionUpdater.refreshStaff(updated, request, response);
            String targetRoleName = updated.getRole() != null ? updated.getRole().getRoleName() : null;
            String successMessage = "ROLE_ADMIN".equals(targetRoleName)
                    ? "Xác minh email công việc thành công."
                    : "Xác minh email khôi phục thành công. Bạn đã sẵn sàng sử dụng chức năng Quên mật khẩu nội bộ.";
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
        } catch (AppException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return REDIRECT_SELF;
    }

    private boolean hasValidReauth(HttpSession session, Long staffId) {
        if (session == null || staffId == null) {
            return false;
        }
        Object owner = session.getAttribute(REAUTH_OWNER_ATTR);
        Object until = session.getAttribute(REAUTH_UNTIL_ATTR);
        return staffId.equals(owner) && until instanceof Instant deadline && Instant.now().isBefore(deadline);
    }

    private void clearReauthGrant(HttpSession session) {
        if (session != null) {
            session.removeAttribute(REAUTH_OWNER_ATTR);
            session.removeAttribute(REAUTH_UNTIL_ATTR);
        }
    }
}
