package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.security.*;
import com.ddagtech.aureliabooks.service.AccountSecurityService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.core.Authentication;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;

@Controller @RequiredArgsConstructor
public class GoogleProfileController {
    private final CustomerIdentity identity;
    private final AccountSecurityService security;
    private final ObjectProvider<ClientRegistrationRepository> clients;
    @PostMapping("/profile/google/link") public String link(Authentication auth,HttpServletRequest request,@RequestParam(required=false) String password,RedirectAttributes flash){
        try{Long owner=identity.resolve(auth);security.reauthenticate(owner,password,false);return begin(request,owner,"LINK",flash);}
        catch(RuntimeException ex){flash.addFlashAttribute("errorMessage","Không thể liên kết Google. Kiểm tra mật khẩu hiện tại và trạng thái tài khoản.");return "redirect:/profile";}
    }
    @PostMapping("/profile/google/reauth") public String reauth(Authentication auth,HttpServletRequest request,RedirectAttributes flash){return begin(request,identity.resolve(auth),"REAUTH",flash);}
    private String begin(HttpServletRequest request,Long owner,String purpose,RedirectAttributes flash){
        var registrations=clients.getIfAvailable();if(registrations==null||registrations.findByRegistrationId("google")==null){flash.addFlashAttribute("errorMessage","Đăng nhập Google chưa được cấu hình.");return "redirect:/profile";}
        GoogleAccountAction.begin(request.getSession(),owner,purpose);return "redirect:/oauth2/authorization/google";
    }
}
