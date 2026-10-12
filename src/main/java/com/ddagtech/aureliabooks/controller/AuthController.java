package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.service.RegistrationService;
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
    private final RegistrationService registration;
    private final ObjectProvider<ClientRegistrationRepository> clients;

    @GetMapping("/auth/login")
    public String login() { return "auth/login"; }

    @GetMapping("/auth/google/login")
    public String googleLogin(jakarta.servlet.http.HttpServletRequest request) {
        if (!googleEnabled()) return "redirect:/auth/login?error=google";
        request.getSession().setAttribute("GOOGLE_AUTH_ORIGIN", "login");
        return "redirect:/oauth2/authorization/google";
    }

    @GetMapping("/auth/google/register")
    public String googleRegister(jakarta.servlet.http.HttpServletRequest request) {
        if (!googleEnabled()) return "redirect:/auth/register";
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
                           RedirectAttributes redirect) {
        if (errors.hasErrors()) return "auth/register";
        try {
            registration.registerLocal(registerRequest);
        } catch (AppException ex) {
            errors.reject("registration", ex.getMessage());
            return "auth/register";
        } catch (DataIntegrityViolationException ex) {
            errors.reject("duplicate", "Email hoặc số điện thoại này đã được đăng ký.");
            return "auth/register";
        }
        redirect.addFlashAttribute("successMessage", "Đăng ký thành công! Bạn có thể đăng nhập bằng tài khoản vừa tạo.");
        return "redirect:/auth/login";
    }
}
