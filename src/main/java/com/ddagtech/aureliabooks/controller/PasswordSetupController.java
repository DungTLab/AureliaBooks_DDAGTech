package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.request.PasswordSetupRequest;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.ContactChangeService;
import com.ddagtech.aureliabooks.service.UserService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@RequestMapping("/profile/password/setup")
public class PasswordSetupController {
    private final CustomerIdentity identity;
    private final UserService users;
    private final ContactChangeService changes;

    @GetMapping
    public String view(Authentication auth, HttpServletRequest request, Model model) {
        Long owner = identity.resolve(auth);
        if (users.viewProfile(owner).canChangePassword()) return "redirect:/profile";
        model.addAttribute("methods", changes.reauthenticationMethods(owner).stream()
                .filter(method -> !"PASSWORD".equals(method)).toList());
        model.addAttribute("authorized", authorized(request.getSession(), owner));
        return "profile/password-setup";
    }

    @PostMapping("/start")
    public String start(Authentication auth, HttpServletRequest request, @RequestParam String channel,
                        RedirectAttributes flash) {
        Long owner = identity.resolve(auth);
        if (users.viewProfile(owner).canChangePassword()) return "redirect:/profile";
        clear(request.getSession());
        var result = changes.startReauthentication(owner, request.getSession().getId(), "PASSWORD_SETUP",
                channel, HttpRequestUtil.getClientIp(request));
        flash.addFlashAttribute("contactMessage", result.message());
        return result.challengeIssued() ? "redirect:/profile/contact-change/verification"
                : "redirect:/profile/password/setup";
    }

    @PostMapping
    public String save(Authentication auth, HttpServletRequest request, HttpServletResponse response,
                       @Valid @ModelAttribute PasswordSetupRequest form, BindingResult errors,
                       RedirectAttributes flash) {
        Long owner = identity.resolve(auth);
        if (!authorized(request.getSession(), owner)) {
            flash.addFlashAttribute("errorMessage", "Vui lòng xác minh OTP trước khi đặt mật khẩu.");
            return "redirect:/profile/password/setup";
        }
        if (errors.hasErrors()) {
            flash.addFlashAttribute("errorMessage", errors.getAllErrors().getFirst().getDefaultMessage());
            return "redirect:/profile/password/setup";
        }
        users.setupPassword(owner, form, HttpRequestUtil.getClientIp(request));
        clear(request.getSession());
        new SecurityContextLogoutHandler().logout(request, response, auth);
        return "redirect:/auth/login?passwordChanged";
    }

    private boolean authorized(HttpSession session, Long owner) {
        return owner.equals(session.getAttribute("CONTACT_REAUTH_OWNER"))
                && "PASSWORD_SETUP".equals(session.getAttribute("CONTACT_REAUTH_TYPE"))
                && session.getAttribute("CONTACT_REAUTH_UNTIL") instanceof Instant expires
                && Instant.now().isBefore(expires);
    }

    private void clear(HttpSession session) {
        session.removeAttribute("CONTACT_REAUTH_OWNER");
        session.removeAttribute("CONTACT_REAUTH_TYPE");
        session.removeAttribute("CONTACT_REAUTH_UNTIL");
    }
}
