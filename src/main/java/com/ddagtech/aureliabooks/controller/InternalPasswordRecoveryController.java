package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.service.AccountOtpService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/internal/forgot-password")
@RequiredArgsConstructor
public class InternalPasswordRecoveryController {

    private static final String FLOW = "INTERNAL_RECOVERY_FLOW";
    private static final String START = "redirect:/internal/forgot-password";
    private static final String VERIFY = START + "?step=verify";
    private static final String SENT = "Nếu email đủ điều kiện khôi phục, bạn sẽ nhận được mã xác thực.";

    private final AccountOtpService otp;

    @GetMapping
    public String view(@RequestParam(required = false) String step, HttpServletRequest request, Model model) {
        var session = request.getSession(false);
        String id = session == null ? null : (String) session.getAttribute(FLOW);
        boolean showCode = "verify".equals(step) && id != null;
        var flow = showCode ? otp.view(id, session.getId(), true) : null;
        model.addAttribute("showCode", showCode);
        model.addAttribute("authorized", flow != null && flow.authorized());
        return "internal/forgot-password";
    }

    @PostMapping("/send")
    public String send(@RequestParam String email, HttpServletRequest request, RedirectAttributes flash) {
        var session = request.getSession();
        // Render the same response when the account is ineligible or mail cannot be sent.
        String id = "";
        try {
            id = otp.internalRecovery(email, session.getId(), HttpRequestUtil.getClientIp(request));
        } catch (IllegalArgumentException ignored) {
            // Account status and delivery results are private.
        }
        session.setAttribute(FLOW, id);
        flash.addFlashAttribute("recoveryMessage", SENT);
        return VERIFY;
    }

    @PostMapping("/resend")
    public String resend(HttpServletRequest request, RedirectAttributes flash) {
        var session = request.getSession();
        try {
            otp.resend((String) session.getAttribute(FLOW), session.getId(), HttpRequestUtil.getClientIp(request), true);
        } catch (IllegalArgumentException ignored) {
            // Use the same response for missing flows and rate limits.
        }
        flash.addFlashAttribute("recoveryMessage", SENT);
        return VERIFY;
    }

    @PostMapping("/verify")
    public String verify(@RequestParam String code, HttpServletRequest request, RedirectAttributes flash) {
        var session = request.getSession();
        try {
            otp.verify((String) session.getAttribute(FLOW), session.getId(), code.strip(),
                    HttpRequestUtil.getClientIp(request), true);
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("recoveryMessage", "Mã không hợp lệ hoặc đã hết hạn.");
        }
        return VERIFY;
    }

    @PostMapping("/reset")
    public String reset(
            @RequestParam String password, @RequestParam String confirmation,
            HttpServletRequest request, HttpServletResponse response, Authentication authentication,
            RedirectAttributes flash) {
        var session = request.getSession();
        String id = (String) session.getAttribute(FLOW);
        var flow = otp.view(id, session.getId(), true);
        if (flow == null || !flow.authorized()) {
            flash.addFlashAttribute("recoveryMessage", "Vui lòng xác minh mã trước khi đặt mật khẩu.");
            return VERIFY;
        }
        try {
            otp.reset(id, session.getId(), password, confirmation, HttpRequestUtil.getClientIp(request), true);
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("recoveryMessage", ex.getMessage());
            return VERIFY;
        }
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        flash.addFlashAttribute("successMessage", "Đã đặt mật khẩu mới. Vui lòng đăng nhập lại.");
        return "redirect:/internal/login";
    }
}
