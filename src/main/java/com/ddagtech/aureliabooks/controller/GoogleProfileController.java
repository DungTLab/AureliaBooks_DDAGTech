package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.security.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class GoogleProfileController {

    private final CustomerIdentity identity;
    private final ObjectProvider<ClientRegistrationRepository> clients;

    @PostMapping("/profile/google/reauth")
    public String reauth(
        Authentication auth,
        HttpServletRequest request,
        RedirectAttributes flash,
        @RequestParam(defaultValue = "EMAIL") String type
    ) {
        request.getSession().setAttribute("GOOGLE_REAUTH_TYPE", "PHONE".equals(type) ? "PHONE" : "EMAIL");
        return begin(request, identity.resolve(auth), "REAUTH", flash);
    }

    private String begin(HttpServletRequest request, Long owner, String purpose, RedirectAttributes flash) {
        var registrations = clients.getIfAvailable();
        if (registrations == null || registrations.findByRegistrationId("google") == null) {
            flash.addFlashAttribute("errorMessage", "Đăng nhập Google chưa được cấu hình.");
            return "redirect:/profile";
        }
        GoogleAccountAction.begin(request.getSession(), owner, purpose);
        return "redirect:/oauth2/authorization/google";
    }
}
