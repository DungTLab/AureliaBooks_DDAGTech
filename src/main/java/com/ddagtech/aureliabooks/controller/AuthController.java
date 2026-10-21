package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.exception.AppException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class AuthController {
    private final ObjectProvider<ClientRegistrationRepository> clients;
    private final ObjectProvider<com.ddagtech.aureliabooks.service.AccountOtpService> accountOtp;

    @ModelAttribute("authPage")
    public String authPage(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getServletPath();
        return path.isEmpty() ? request.getRequestURI().substring(request.getContextPath().length()) : path;
    }

    @GetMapping("/auth/login")
    public String login() {
        return "auth/login";
    }

    @GetMapping("/auth/google/login")
    public String googleLogin(jakarta.servlet.http.HttpServletRequest request) {
        if (!googleEnabled()) return "redirect:/auth/login?error=google";
        request.getSession().removeAttribute("GOOGLE_ACCOUNT_ACTION");
        request.getSession().setAttribute("GOOGLE_AUTH_ORIGIN", "login");
        return "redirect:/oauth2/authorization/google";
    }

    @GetMapping("/auth/google/register")
    public String googleRegister(jakarta.servlet.http.HttpServletRequest request) {
        if (!googleEnabled()) return "redirect:/auth/register";
        request.getSession().removeAttribute("GOOGLE_ACCOUNT_ACTION");
        request.getSession().setAttribute("GOOGLE_AUTH_ORIGIN", "register");
        return "redirect:/oauth2/authorization/google";
    }

    @ModelAttribute("googleEnabled")
    public boolean googleEnabled() {
        var repository = clients.getIfAvailable();
        return repository != null && repository.findByRegistrationId("google") != null;
    }

    @GetMapping("/auth/register")
    public String register(Model model) {
        model.addAttribute("registerRequest", new RegisterRequest(null, null, null, null, null, null, null, false));
        return "auth/register";
    }

    @PostMapping("/auth/register")
    public String register(@Valid @ModelAttribute RegisterRequest registerRequest, BindingResult errors,
                           RedirectAttributes redirect, jakarta.servlet.http.HttpServletRequest request) {
        if (errors.hasErrors()) return "auth/register";
        try {
            var local = new RegisterRequest(null, registerRequest.password(), registerRequest.fullName(), registerRequest.phone(), registerRequest.dob(), registerRequest.confirmPassword(), registerRequest.gender(), registerRequest.agreeTerms());
            String flow = accountOtp.getObject().registration(local, request.getSession().getId(), com.ddagtech.aureliabooks.util.HttpRequestUtil.getClientIp(request));
            request.getSession().setAttribute("ACCOUNT_OTP_FLOW", flow);
        } catch (IllegalArgumentException ex) {
            errors.reject("registration", ex.getMessage());
            return "auth/register";
        } catch (AppException ex) {
            errors.reject("registration", ex.getMessage());
            return "auth/register";
        } catch (DataIntegrityViolationException ex) {
            errors.reject("duplicate", "Email hoặc số điện thoại này đã được đăng ký.");
            return "auth/register";
        }
        redirect.addFlashAttribute("verificationMessage", "Đã gửi mã tới số điện thoại. Nhập mã để hoàn tất đăng ký.");
        return "redirect:/auth/verification";
    }
}
