package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.security.GoogleLinkSessionAuthenticator;
import com.ddagtech.aureliabooks.service.AccountOtpService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class GoogleLinkController {

    private final AccountOtpService otp;
    private final GoogleLinkSessionAuthenticator authenticator;

    @GetMapping("/auth/google-link")
    public String view(HttpServletRequest request, Model model, RedirectAttributes flash) {
        var session = request.getSession();
        try {
            var view = otp.googleLinkView(flow(request), session.getId());
            if (view == null) {
                session.removeAttribute("GOOGLE_LINK_FLOW");
                flash.addFlashAttribute("errorMessage", "Yêu cầu liên kết đã hết hạn. Vui lòng đăng nhập Google lại.");
                return "redirect:/auth/login";
            }
            model.addAttribute("link", view);
            return "auth/google-link";
        } catch (RuntimeException ex) {
            session.removeAttribute("GOOGLE_LINK_FLOW");
            flash.addFlashAttribute(
                "errorMessage",
                "Không thể tiếp tục liên kết. Vui lòng đăng nhập bằng phương thức hiện có."
            );
            return "redirect:/auth/login";
        }
    }

    @PostMapping("/auth/google-link/send")
    public String send(
        HttpServletRequest request,
        @RequestParam(required = false) String channel,
        RedirectAttributes flash
    ) {
        try {
            otp.sendGoogleLink(
                flow(request),
                request.getSession().getId(),
                channel,
                HttpRequestUtil.getClientIp(request)
            );
            flash.addFlashAttribute("linkMessage", "Đã gửi mã. Vui lòng kiểm tra kênh xác minh của bạn.");
        } catch (RuntimeException ex) {
            flash.addFlashAttribute("linkMessage", message(ex));
        }
        return "redirect:/auth/google-link";
    }

    @PostMapping("/auth/google-link/verify")
    public void verify(HttpServletRequest request, HttpServletResponse response, @RequestParam String code)
        throws IOException {
        try {
            var user = otp.verifyGoogleLink(
                flow(request),
                request.getSession().getId(),
                code,
                HttpRequestUtil.getClientIp(request)
            );
            authenticator.login(user, request, response);
        } catch (RuntimeException ex) {
            // Use the MVC redirect path so the flash message is saved by DispatcherServlet.
            throw new GoogleLinkVerificationException(message(ex));
        }
    }

    @ExceptionHandler(GoogleLinkVerificationException.class)
    public String verificationError(GoogleLinkVerificationException ex, RedirectAttributes flash) {
        flash.addFlashAttribute("linkMessage", ex.getMessage());
        return "redirect:/auth/google-link";
    }

    private String flow(HttpServletRequest request) {
        return (String) request.getSession().getAttribute("GOOGLE_LINK_FLOW");
    }

    private String message(RuntimeException ex) {
        return ex instanceof IllegalArgumentException
            ? ex.getMessage()
            : "Không thể xác minh liên kết. Vui lòng thử lại hoặc đăng nhập bằng mật khẩu.";
    }

    static class GoogleLinkVerificationException extends RuntimeException {

        GoogleLinkVerificationException(String message) {
            super(message);
        }
    }
}
