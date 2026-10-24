package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.service.AccountOtpService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class AccountVerificationController {

    private final AccountOtpService otp;

    @GetMapping("/auth/forgot-password")
    public String forgot() {
        return "auth/forgot-password";
    }

    @PostMapping("/auth/forgot-password/lookup")
    public String lookup(
        @RequestParam String identifier,
        @RequestParam(required = false) String channel,
        HttpServletRequest request,
        Model model,
        RedirectAttributes flash
    ) {
        identifier = identifier.strip();
        var session = request.getSession();
        session.removeAttribute("RECOVERY_IDENTIFIER");
        session.removeAttribute("ACCOUNT_OTP_FLOW");
        model.addAttribute("identifier", identifier);
        model.addAttribute("selectedChannel", "SMS".equals(channel) ? "SMS" : "EMAIL");
        try {
            if (
                channel != null &&
                (!List.of("EMAIL", "SMS").contains(channel) ||
                    ("SMS".equals(channel) && !identifier.matches("0[0-9]{9}")) ||
                    ("EMAIL".equals(channel) && !identifier.contains("@")))
            ) {
                throw new IllegalArgumentException("Vui lòng nhập thông tin phù hợp với phương thức nhận mã đã chọn.");
            }
            var channels = otp.recoveryChannels(identifier);
            if (channel != null) {
                if (!channels.contains(channel)) {
                    throw new IllegalArgumentException("Thông tin này chưa được xác minh để nhận mã khôi phục.");
                }
                return startRecovery(identifier, channel, request, flash);
            }
            session.setAttribute("RECOVERY_IDENTIFIER", identifier);
            if (channels.size() == 1) return startRecovery(identifier, channels.getFirst(), request, flash);
            model.addAttribute("channels", channels);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("recoveryError", ex.getMessage());
        }
        return "auth/forgot-password";
    }

    @PostMapping("/auth/forgot-password/send")
    public String send(
        @RequestParam(defaultValue = "SMS") String channel,
        HttpServletRequest request,
        RedirectAttributes flash
    ) {
        String identifier = (String) request.getSession().getAttribute("RECOVERY_IDENTIFIER");
        if (identifier == null) {
            flash.addFlashAttribute("recoveryError", "Vui lòng nhập email hoặc số điện thoại để tìm tài khoản trước.");
            return "redirect:/auth/forgot-password";
        }
        return startRecovery(identifier, channel, request, flash);
    }

    private String startRecovery(
        String identifier,
        String channel,
        HttpServletRequest request,
        RedirectAttributes flash
    ) {
        var session = request.getSession();
        session.removeAttribute("ACCOUNT_OTP_FLOW");
        try {
            String id = otp.recovery(identifier, channel, session.getId(), HttpRequestUtil.getClientIp(request));
            session.setAttribute("ACCOUNT_OTP_FLOW", id);
            session.removeAttribute("RECOVERY_IDENTIFIER");
            flash.addFlashAttribute("verificationMessage", otp.recoveryMessage());
            return "redirect:/auth/verification";
        } catch (RuntimeException ex) {
            flash.addFlashAttribute("identifier", identifier);
            flash.addFlashAttribute("selectedChannel", channel);
            flash.addFlashAttribute(
                "recoveryError",
                ex instanceof IllegalArgumentException
                    ? ex.getMessage()
                    : "Không thể gửi mã xác thực lúc này. Vui lòng thử lại."
            );
            return "redirect:/auth/forgot-password";
        }
    }

    @GetMapping("/auth/verification")
    public String view(HttpServletRequest request, Model model) {
        var s = request.getSession();
        var flow = otp.view((String) s.getAttribute("ACCOUNT_OTP_FLOW"), s.getId());
        if (flow == null) return "redirect:/auth/forgot-password";
        model.addAttribute("verification", flow);
        return "auth/verification";
    }

    @PostMapping("/auth/verification/verify")
    public String verify(@RequestParam String code, HttpServletRequest request, RedirectAttributes flash) {
        var s = request.getSession();
        try {
            var result = otp.verify(
                (String) s.getAttribute("ACCOUNT_OTP_FLOW"),
                s.getId(),
                code.strip(),
                HttpRequestUtil.getClientIp(request)
            );
            if (result.complete()) {
                s.removeAttribute("ACCOUNT_OTP_FLOW");
                flash.addFlashAttribute("successMessage", result.message());
                return "redirect:/auth/login";
            }
            flash.addFlashAttribute("verificationMessage", result.message());
        } catch (RuntimeException ex) {
            flash.addFlashAttribute(
                "verificationMessage",
                ex instanceof IllegalArgumentException
                    ? ex.getMessage()
                    : "Không thể xác minh. Vui lòng thử lại hoặc bắt đầu lại."
            );
        }
        return "redirect:/auth/verification";
    }

    @PostMapping("/auth/verification/resend")
    public String resend(HttpServletRequest request, RedirectAttributes flash) {
        var s = request.getSession();
        try {
            otp.resend((String) s.getAttribute("ACCOUNT_OTP_FLOW"), s.getId(), HttpRequestUtil.getClientIp(request));
            flash.addFlashAttribute("verificationMessage", otp.recoveryMessage());
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("verificationMessage", ex.getMessage());
        }
        return "redirect:/auth/verification";
    }

    @PostMapping("/auth/verification/reset")
    public String reset(
        @RequestParam String password,
        @RequestParam String confirmation,
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication auth,
        RedirectAttributes flash
    ) {
        var s = request.getSession();
        try {
            otp.reset(
                (String) s.getAttribute("ACCOUNT_OTP_FLOW"),
                s.getId(),
                password,
                confirmation,
                HttpRequestUtil.getClientIp(request)
            );
            new SecurityContextLogoutHandler().logout(request, response, auth);
            flash.addFlashAttribute("successMessage", "Đã đặt mật khẩu mới. Vui lòng đăng nhập.");
            return "redirect:/auth/login";
        } catch (RuntimeException ex) {
            flash.addFlashAttribute(
                "verificationMessage",
                ex instanceof IllegalArgumentException
                    ? ex.getMessage()
                    : "Không thể đặt mật khẩu. Vui lòng bắt đầu lại."
            );
            return "redirect:/auth/verification";
        }
    }
}
