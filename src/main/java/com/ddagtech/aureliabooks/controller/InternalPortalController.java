package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.request.PasswordChangeRequest;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.security.AuthenticatedSessionUpdater;
import com.ddagtech.aureliabooks.security.StaffIdentity;
import com.ddagtech.aureliabooks.service.AccountSecurityService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/** Dedicated password-only entrance and self-service account utilities for business staff. */
@Controller
@RequiredArgsConstructor
public class InternalPortalController {

    private final StaffIdentity staffIdentity;
    private final AccountSecurityService accountSecurityService;
    private final AuthenticatedSessionUpdater authenticatedSessionUpdater;

    @GetMapping("/internal/login")
    public String login() {
        return "internal/login";
    }

    @GetMapping({"/internal", "/internal/"})
    public String workspace() {
        return "redirect:/dashboard";
    }

    @PostMapping("/internal/account/change-password")
    public Object changePassword(
            Authentication authentication,
            @Valid @ModelAttribute("internalPasswordForm") PasswordChangeRequest form,
            BindingResult bindingResult,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes flash
    ) {
        boolean isJson = request.getHeader("Accept") != null && request.getHeader("Accept").contains("application/json");
        Long staffId = staffIdentity.resolve(authentication);

        if (bindingResult.hasErrors()) {
            String errorMsg = bindingResult.getAllErrors().getFirst().getDefaultMessage();
            if (isJson) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", errorMsg != null ? errorMsg : "Dữ liệu không hợp lệ"));
            }
            flash.addFlashAttribute("errorMessage", errorMsg);
            flash.addFlashAttribute("openPasswordModal", true);
            flash.addFlashAttribute("internalPasswordError", errorMsg);
            return redirectBack(request);
        }

        try {
            HttpSession session = request.getSession(false);
            String sessionId = session != null ? session.getId() : null;
            String clientIp = HttpRequestUtil.getClientIp(request);

            User updated = accountSecurityService.changeStaffPassword(staffId, form, sessionId, clientIp);
            authenticatedSessionUpdater.refreshStaff(updated, request, response);

            if (isJson) {
                return ResponseEntity.ok(Map.of("success", true, "message", "Đổi mật khẩu thành công. Các phiên đăng nhập khác đã được thu hồi."));
            }
            flash.addFlashAttribute("successMessage", "Đổi mật khẩu thành công. Các phiên đăng nhập khác đã được thu hồi.");
        } catch (AppException e) {
            if (isJson) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
            }
            flash.addFlashAttribute("errorMessage", e.getMessage());
            flash.addFlashAttribute("openPasswordModal", true);
            flash.addFlashAttribute("internalPasswordError", e.getMessage());
        }

        return redirectBack(request);
    }

    private String redirectBack(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank() && !referer.contains("/internal/login")) {
            return "redirect:" + referer;
        }
        return "redirect:/dashboard";
    }
}
