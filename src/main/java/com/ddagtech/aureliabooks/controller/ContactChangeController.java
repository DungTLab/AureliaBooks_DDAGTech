package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.ContactChangeService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@RequestMapping("/profile/contact-change")
public class ContactChangeController {

    private final ContactChangeService changes;
    private final CustomerIdentity identity;
    private final com.ddagtech.aureliabooks.service.AccountSecurityService security;

    @GetMapping
    public String view(Authentication auth, HttpServletRequest request, Model model,
                       @RequestParam(defaultValue = "EMAIL") String type) {
        Long owner = identity.resolve(auth);
        var customer = security.contactSettings(owner);
        model.addAttribute("passwordRequired", customer.passwordRequired());
        var flow = changes.view(owner, request.getSession().getId());
        model.addAttribute("change", flow);
        String selectedType = "PHONE".equals(type) ? "PHONE" : "EMAIL";
        boolean hasContact = "PHONE".equals(selectedType) ? customer.phonePresent() : customer.emailPresent();
        model.addAttribute("selectedType", selectedType);
        model.addAttribute("addingContact", !hasContact);
        return "profile/contact-change";
    }

    @GetMapping("/verification")
    public String verification(Authentication auth, HttpServletRequest request, Model model) {
        var flow = changes.view(identity.resolve(auth), request.getSession().getId());
        if (flow == null) {
            return "redirect:/profile/contact-change";
        }
        model.addAttribute("change", flow);
        return "profile/contact-verification";
    }

    @PostMapping("/start")
    public String start(Authentication auth, HttpServletRequest request,
                        @RequestParam String type, @RequestParam String value, @RequestParam(required = false) String password, RedirectAttributes flash) {
        try {
            Long owner = identity.resolve(auth);
            var s = request.getSession();
            Object grant = s.getAttribute("GOOGLE_REAUTH_OWNER"), until = s.getAttribute("GOOGLE_REAUTH_UNTIL");
            boolean googleProof = owner.equals(grant) && until instanceof java.time.Instant expires && java.time.Instant.now().isBefore(expires);
            security.reauthenticate(owner, password, googleProof);
            s.removeAttribute("GOOGLE_REAUTH_OWNER");
            s.removeAttribute("GOOGLE_REAUTH_UNTIL");
            var result = changes.startDestination(owner, s.getId(), type, value, HttpRequestUtil.getClientIp(request));
            flash.addFlashAttribute("contactMessage", result.message());
            if (changes.view(owner, s.getId()) != null) {
                return "redirect:/profile/contact-change/verification";
            }
        } catch (org.springframework.security.core.AuthenticationException | IllegalArgumentException ex) {
            flash.addFlashAttribute("contactMessage", ex.getMessage());
        }
        return "redirect:/profile/contact-change?type=" + ("PHONE".equals(type) ? "PHONE" : "EMAIL");
    }

    @PostMapping("/resend")
    public String resend(Authentication auth, HttpServletRequest request, RedirectAttributes flash) {
        var result = changes.resend(identity.resolve(auth), request.getSession().getId(), HttpRequestUtil.getClientIp(request));
        flash.addFlashAttribute("contactMessage", result.message());
        return "redirect:/profile/contact-change/verification";
    }

    @PostMapping("/verify")
    public String verify(Authentication auth, HttpServletRequest request, HttpServletResponse response,
                         @RequestParam String code, RedirectAttributes flash) {
        var result = changes.verify(identity.resolve(auth), request.getSession().getId(), code.strip(), HttpRequestUtil.getClientIp(request));
        if (result.completed()) {
            flash.addFlashAttribute("successMessage", result.message());
            if (result.loginRequired()) {
                new SecurityContextLogoutHandler().logout(request, response, auth);
                return "redirect:/auth/login";
            }
            return "redirect:/profile";
        }
        flash.addFlashAttribute("contactMessage", result.message());
        return "redirect:/profile/contact-change/verification";
    }
}
