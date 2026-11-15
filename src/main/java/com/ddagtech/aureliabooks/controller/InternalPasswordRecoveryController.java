package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.service.AccountOtpService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Slf4j
@Controller
@RequestMapping("/internal/forgot-password")
@RequiredArgsConstructor
public class InternalPasswordRecoveryController {

    private static final String FLOW = "INTERNAL_RECOVERY_FLOW";
    private static final String FLOW_AUTHORIZED = "INTERNAL_RECOVERY_AUTHORIZED";
    private static final String FLOW_EXPIRES_AT = "INTERNAL_RECOVERY_EXPIRES_AT";
    private static final String FLOW_RESEND_AT = "INTERNAL_RECOVERY_RESEND_AT";
    private static final String FLOW_ATTEMPTS = "INTERNAL_RECOVERY_ATTEMPTS";

    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final long OTP_TTL_SECONDS = 300;
    private static final long RESEND_COOLDOWN_SECONDS = 60;

    private static final String START = "redirect:/internal/forgot-password";
    private static final String VERIFY = START + "?step=verify";
    private static final String SENT = "Nếu email đủ điều kiện khôi phục, bạn sẽ nhận được mã xác thực.";
    private static final String INVALID_OR_EXPIRED_CODE = "Mã không hợp lệ hoặc đã hết hạn.";
    private static final String EXPIRED_FLOW_MESSAGE =
            "Phiên khôi phục mật khẩu đã hết hạn hoặc không còn hiệu lực. Vui lòng yêu cầu mã mới.";
    private static final String MAX_ATTEMPTS_MESSAGE =
            "Mã không hợp lệ hoặc đã hết hạn. Vui lòng yêu cầu mã mới.";
    private static final String CODE_VERIFIED = "Mã hợp lệ. Hãy đặt mật khẩu mới.";
    private static final String VERIFY_FIRST = "Vui lòng xác minh mã trước khi đặt mật khẩu.";
    private static final String PASSWORD_RESET_SUCCESS = "Đã đặt mật khẩu mới. Vui lòng đăng nhập lại.";

    private final AccountOtpService otp;

    @GetMapping
    public String view(
            @RequestParam(required = false) String step,
            HttpServletRequest request,
            Model model,
            RedirectAttributes flash
    ) {
        var session = request.getSession(false);
        if (!"verify".equals(step)) {
            clearRecoverySession(session);
            model.addAttribute("showCode", false);
            model.addAttribute("authorized", false);
            return "internal/forgot-password";
        }

        String id = session == null ? null : (String) session.getAttribute(FLOW);
        if (session == null || id == null) {
            clearRecoverySession(session);
            return START;
        }

        var flow = otp.view(id, session.getId(), true);
        boolean nonBlankFlowMissing = !id.isBlank() && flow == null;
        boolean exhausted = attemptsOf(session) >= MAX_VERIFY_ATTEMPTS;
        boolean expiredResetGrant = isAuthorizedSession(session) && (flow == null || !flow.authorized());
        if (nonBlankFlowMissing || exhausted || expiredResetGrant) {
            log.debug("Restarting dead internal recovery session (blankId={}, flowPresent={}, exhausted={})",
                    id.isBlank(), flow != null, exhausted);
            clearRecoverySession(session);
            preserveOrFlash(model, flash, exhausted ? MAX_ATTEMPTS_MESSAGE : EXPIRED_FLOW_MESSAGE);
            return START;
        }

        boolean authorized = flow != null && flow.authorized();
        model.addAttribute("showCode", true);
        model.addAttribute("authorized", authorized);
        if (!authorized) {
            // Compute countdown timers strictly from session timestamps to avoid eligible/ineligible timing leaks.
            model.addAttribute("otpExpiresSeconds", remainingSeconds(session, FLOW_EXPIRES_AT,
                    flow != null ? flow.expiresIn() : OTP_TTL_SECONDS));
            model.addAttribute("otpResendSeconds", remainingSeconds(session, FLOW_RESEND_AT,
                    flow != null ? flow.resendIn() : 0L));
        }
        return "internal/forgot-password";
    }

    @PostMapping("/send")
    public String send(@RequestParam String email, HttpServletRequest request, RedirectAttributes flash) {
        var session = request.getSession();
        // Render the same response when the account is ineligible or mail cannot be sent.
        String id = "";
        try {
            id = otp.internalRecovery(email, session.getId(), HttpRequestUtil.getClientIp(request));
        } catch (IllegalArgumentException ex) {
            // Account status and delivery results are private; SMTP failures are logged inside AccountOtpServiceImpl.
            log.debug("Internal recovery send stayed in dummy state: {}", ex.getMessage());
        }
        startRecoverySession(session, id);
        flash.addFlashAttribute("recoveryMessage", SENT);
        return VERIFY;
    }

    @PostMapping("/resend")
    public String resend(HttpServletRequest request, RedirectAttributes flash) {
        var session = request.getSession(false);
        String id = session == null ? null : (String) session.getAttribute(FLOW);
        if (session == null || id == null) {
            clearRecoverySession(session);
            flash.addFlashAttribute("recoveryMessage", EXPIRED_FLOW_MESSAGE);
            return START;
        }
        if (isAuthorizedSession(session)) {
            var flow = otp.view(id, session.getId(), true);
            if (flow != null && flow.authorized()) {
                return VERIFY;
            }
            clearRecoverySession(session);
            flash.addFlashAttribute("recoveryMessage", EXPIRED_FLOW_MESSAGE);
            return START;
        }
        try {
            otp.resend(id, session.getId(), HttpRequestUtil.getClientIp(request), true);
        } catch (IllegalArgumentException ex) {
            // Use the same response for missing flows and rate limits.
            log.debug("Internal recovery resend rejected: {}", ex.getMessage());
        }
        refreshResendSession(session);
        flash.addFlashAttribute("recoveryMessage", SENT);
        return VERIFY;
    }

    @PostMapping("/verify")
    public String verify(@RequestParam String code, HttpServletRequest request, RedirectAttributes flash) {
        var session = request.getSession(false);
        String id = session == null ? null : (String) session.getAttribute(FLOW);
        if (session == null || id == null) {
            clearRecoverySession(session);
            flash.addFlashAttribute("recoveryMessage", EXPIRED_FLOW_MESSAGE);
            return START;
        }
        if (isAuthorizedSession(session)) {
            var flow = otp.view(id, session.getId(), true);
            if (flow != null && flow.authorized()) {
                return VERIFY;
            }
            clearRecoverySession(session);
            flash.addFlashAttribute("recoveryMessage", EXPIRED_FLOW_MESSAGE);
            return START;
        }
        try {
            var result = otp.verify(id, session.getId(), code == null ? "" : code.strip(),
                    HttpRequestUtil.getClientIp(request), true);
            markSessionAuthorized(session);
            String verifiedMessage = result != null && result.message() != null && !result.message().isBlank()
                    ? result.message()
                    : CODE_VERIFIED;
            flash.addFlashAttribute("recoveryMessage", verifiedMessage);
            return VERIFY;
        } catch (IllegalArgumentException ex) {
            int nextAttempts = incrementAttempts(session);
            if (nextAttempts >= MAX_VERIFY_ATTEMPTS) {
                clearRecoverySession(session);
                flash.addFlashAttribute("recoveryMessage", MAX_ATTEMPTS_MESSAGE);
                return START;
            }
            flash.addFlashAttribute("recoveryMessage", INVALID_OR_EXPIRED_CODE);
            return VERIFY;
        }
    }

    @PostMapping("/reset")
    public String reset(
            @RequestParam String password, @RequestParam String confirmation,
            HttpServletRequest request, HttpServletResponse response, Authentication authentication,
            RedirectAttributes flash) {
        var session = request.getSession(false);
        String id = session == null ? null : (String) session.getAttribute(FLOW);
        if (session == null || id == null) {
            clearRecoverySession(session);
            flash.addFlashAttribute("recoveryMessage", EXPIRED_FLOW_MESSAGE);
            return START;
        }
        var flow = otp.view(id, session.getId(), true);
        if (flow == null || !flow.authorized()) {
            if (isAuthorizedSession(session) || !id.isBlank()) {
                clearRecoverySession(session);
                flash.addFlashAttribute("recoveryMessage", EXPIRED_FLOW_MESSAGE);
                return START;
            }
            flash.addFlashAttribute("recoveryMessage", VERIFY_FIRST);
            return VERIFY;
        }
        try {
            otp.reset(id, session.getId(), password, confirmation, HttpRequestUtil.getClientIp(request), true);
        } catch (IllegalArgumentException ex) {
            if (otp.view(id, session.getId(), true) == null) {
                clearRecoverySession(session);
                flash.addFlashAttribute("recoveryMessage", EXPIRED_FLOW_MESSAGE);
                return START;
            }
            flash.addFlashAttribute("recoveryMessage", ex.getMessage());
            return VERIFY;
        }
        clearRecoverySession(session);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        flash.addFlashAttribute("successMessage", PASSWORD_RESET_SUCCESS);
        return "redirect:/internal/login";
    }

    private static void startRecoverySession(HttpSession session, String id) {
        Instant now = Instant.now();
        session.setAttribute(FLOW, id);
        session.removeAttribute(FLOW_AUTHORIZED);
        session.setAttribute(FLOW_EXPIRES_AT, now.plusSeconds(OTP_TTL_SECONDS));
        session.setAttribute(FLOW_RESEND_AT, now.plusSeconds(RESEND_COOLDOWN_SECONDS));
        session.setAttribute(FLOW_ATTEMPTS, 0);
    }

    private static void refreshResendSession(HttpSession session) {
        Instant now = Instant.now();
        Instant resendAt = (Instant) session.getAttribute(FLOW_RESEND_AT);
        if (resendAt == null || !now.isBefore(resendAt)) {
            session.setAttribute(FLOW_EXPIRES_AT, now.plusSeconds(OTP_TTL_SECONDS));
            session.setAttribute(FLOW_RESEND_AT, now.plusSeconds(RESEND_COOLDOWN_SECONDS));
            session.setAttribute(FLOW_ATTEMPTS, 0);
        }
    }

    private static void markSessionAuthorized(HttpSession session) {
        session.setAttribute(FLOW_AUTHORIZED, Boolean.TRUE);
        session.setAttribute(FLOW_EXPIRES_AT, Instant.now().plusSeconds(OTP_TTL_SECONDS));
        session.setAttribute(FLOW_ATTEMPTS, 0);
    }

    private static void clearRecoverySession(HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(FLOW);
        session.removeAttribute(FLOW_AUTHORIZED);
        session.removeAttribute(FLOW_EXPIRES_AT);
        session.removeAttribute(FLOW_RESEND_AT);
        session.removeAttribute(FLOW_ATTEMPTS);
    }

    private static boolean isAuthorizedSession(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute(FLOW_AUTHORIZED));
    }

    private static int attemptsOf(HttpSession session) {
        Object value = session.getAttribute(FLOW_ATTEMPTS);
        return value instanceof Integer count ? count : 0;
    }

    private static int incrementAttempts(HttpSession session) {
        int next = attemptsOf(session) + 1;
        session.setAttribute(FLOW_ATTEMPTS, next);
        return next;
    }

    private static long remainingSeconds(HttpSession session, String attribute, long fallbackSeconds) {
        Object value = session.getAttribute(attribute);
        if (!(value instanceof Instant deadline)) {
            return Math.max(0L, fallbackSeconds);
        }
        return Math.max(0L, Duration.between(Instant.now(), deadline).getSeconds());
    }

    private static void preserveOrFlash(Model model, RedirectAttributes flash, String fallbackMessage) {
        Object existing = model.getAttribute("recoveryMessage");
        flash.addFlashAttribute("recoveryMessage", existing instanceof String text && !text.isBlank() ? text : fallbackMessage);
    }
}
