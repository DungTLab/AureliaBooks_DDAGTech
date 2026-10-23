package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.security.AuthenticatedSessionUpdater;
import com.ddagtech.aureliabooks.service.ContactChangeService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@RequestMapping("/profile/contact-change")
public class ContactChangeController {

    private final ContactChangeService changes;
    private final AuthenticatedSessionUpdater sessionUpdater;
    private final CustomerIdentity identity;
    private final com.ddagtech.aureliabooks.service.AccountSecurityService security;

    @GetMapping
    public String view(Authentication auth, HttpServletRequest request, Model model,
                       @RequestParam(defaultValue = "EMAIL") String type) {
        Long owner = identity.resolve(auth);
        var customer = security.contactSettings(owner);
        model.addAttribute("passwordRequired", customer.passwordRequired());
        model.addAttribute("verificationMethods", changes.reauthenticationMethods(owner));
        var flow = changes.view(owner, request.getSession().getId());
        model.addAttribute("change", flow);
        String selectedType = "PHONE".equals(type) ? "PHONE" : "EMAIL";
        boolean hasContact = "PHONE".equals(selectedType) ? customer.phonePresent() : customer.emailPresent();
        model.addAttribute("selectedType", selectedType);
        model.addAttribute("reauthenticated", authorized(request.getSession(), owner, selectedType));
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

    @PostMapping("/reauth")
    public String reauthenticate(Authentication auth, HttpServletRequest request,
            @RequestParam(defaultValue = "EMAIL") String type,
            @RequestParam(required = false) String password,
            @RequestParam(defaultValue = "PASSWORD") String method, RedirectAttributes flash) {
        String selectedType = "PHONE".equals(type) ? "PHONE" : "EMAIL";
        try {
            Long owner = identity.resolve(auth);
            if (!"PASSWORD".equals(method)) {
                var result = changes.startReauthentication(owner, request.getSession().getId(), selectedType, method, HttpRequestUtil.getClientIp(request));
                flash.addFlashAttribute("contactMessage", result.message());
                return result.challengeIssued() ? "redirect:/profile/contact-change/verification"
                        : "redirect:/profile/contact-change?type=" + selectedType;
            }
            security.reauthenticate(owner, password, false);
            var session = request.getSession();
            session.setAttribute("CONTACT_REAUTH_OWNER", owner);
            session.setAttribute("CONTACT_REAUTH_TYPE", selectedType);
            session.setAttribute("CONTACT_REAUTH_UNTIL", java.time.Instant.now().plusSeconds(300));
        } catch (org.springframework.security.core.AuthenticationException | IllegalArgumentException ex) {
            flash.addFlashAttribute("contactMessage", ex.getMessage());
        }
        return "redirect:/profile/contact-change?type=" + selectedType;
    }

    private boolean authorized(HttpSession session, Long owner, String type) {
        return owner.equals(session.getAttribute("CONTACT_REAUTH_OWNER"))
                && type.equals(session.getAttribute("CONTACT_REAUTH_TYPE"))
                && session.getAttribute("CONTACT_REAUTH_UNTIL") instanceof java.time.Instant expires
                && java.time.Instant.now().isBefore(expires);
    }

    @PostMapping("/start")
    public String start(Authentication auth, HttpServletRequest request,
                        @RequestParam String type, @RequestParam String value, RedirectAttributes flash) {
        try {
            Long owner = identity.resolve(auth);
            var s = request.getSession();
            if (!authorized(s, owner, type)) {
                throw new IllegalArgumentException("Vui lòng xác thực tài khoản trước khi nhập thông tin mới.");
            }
            var result = changes.startDestination(owner, s.getId(), type, value, HttpRequestUtil.getClientIp(request));
            flash.addFlashAttribute("contactMessage", result.message());
            if (result.challengeIssued()) {
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
        Long owner = identity.resolve(auth);
        var flow = changes.view(owner, request.getSession().getId());
        var result = changes.verify(owner, request.getSession().getId(), code.strip(), HttpRequestUtil.getClientIp(request));
        if (result.reauthenticated() && flow != null) {
            var session = request.getSession();
            session.setAttribute("CONTACT_REAUTH_OWNER", owner);
            session.setAttribute("CONTACT_REAUTH_TYPE", flow.type());
            session.setAttribute("CONTACT_REAUTH_UNTIL", java.time.Instant.now().plusSeconds(300));
            return "redirect:/profile/contact-change?type=" + flow.type();
        }
        if (result.completed()) {
            flash.addFlashAttribute("successMessage", result.message());
            sessionUpdater.refresh(owner, request, response);
            request.getSession().removeAttribute("CONTACT_REAUTH_OWNER");
            request.getSession().removeAttribute("CONTACT_REAUTH_UNTIL");
            return "redirect:/profile";
        }
        flash.addFlashAttribute("contactMessage", result.message());
        return "redirect:/profile/contact-change/verification";
    }
}
