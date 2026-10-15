package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.service.AccountOtpService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

@Controller @RequiredArgsConstructor
public class AccountVerificationController {
    private final AccountOtpService otp;
    @GetMapping("/auth/forgot-password") public String forgot(){return "auth/forgot-password";}
    @PostMapping("/auth/forgot-password/lookup") public String lookup(@RequestParam String identifier,HttpServletRequest request,Model model){
        identifier=identifier.strip();if(identifier.length()>100)identifier="";
        request.getSession().setAttribute("RECOVERY_IDENTIFIER",identifier);
        var channels=otp.recoveryChannels(identifier);
        // Unknown/ineligible accounts use a dummy channel; no explicit account-existence response.
        if(channels.isEmpty())channels=java.util.List.of(identifier.contains("@")?"EMAIL":"SMS");
        model.addAttribute("channels",channels);return "auth/forgot-password";
    }
    @PostMapping("/auth/forgot-password/send") public String send(@RequestParam(defaultValue="SMS") String channel,HttpServletRequest request,RedirectAttributes flash){
        String identifier=(String)request.getSession().getAttribute("RECOVERY_IDENTIFIER");
        if(identifier==null)return "redirect:/auth/forgot-password";
        try{String id=otp.recovery(identifier,channel,request.getSession().getId(),HttpRequestUtil.getClientIp(request));
            request.getSession().setAttribute("ACCOUNT_OTP_FLOW",id);flash.addFlashAttribute("verificationMessage",otp.recoveryMessage());return "redirect:/auth/verification";
        }catch(IllegalArgumentException ex){flash.addFlashAttribute("verificationMessage",otp.recoveryMessage());return "redirect:/auth/forgot-password";}
    }
    @GetMapping("/auth/verification") public String view(HttpServletRequest request,Model model){
        var s=request.getSession();var flow=otp.view((String)s.getAttribute("ACCOUNT_OTP_FLOW"),s.getId());
        if(flow==null)return "redirect:/auth/forgot-password";model.addAttribute("verification",flow);return "auth/verification";
    }
    @PostMapping("/auth/verification/verify") public String verify(@RequestParam String code,HttpServletRequest request,RedirectAttributes flash){
        var s=request.getSession();try{var result=otp.verify((String)s.getAttribute("ACCOUNT_OTP_FLOW"),s.getId(),code.strip(),HttpRequestUtil.getClientIp(request));
            if(result.complete()){s.removeAttribute("ACCOUNT_OTP_FLOW");flash.addFlashAttribute("successMessage",result.message());return "redirect:/auth/login";}
            flash.addFlashAttribute("verificationMessage",result.message());
        }catch(RuntimeException ex){flash.addFlashAttribute("verificationMessage",ex instanceof IllegalArgumentException?ex.getMessage():"Không thể xác minh. Vui lòng thử lại hoặc bắt đầu lại.");}
        return "redirect:/auth/verification";
    }
    @PostMapping("/auth/verification/resend") public String resend(HttpServletRequest request,RedirectAttributes flash){var s=request.getSession();try{otp.resend((String)s.getAttribute("ACCOUNT_OTP_FLOW"),s.getId(),HttpRequestUtil.getClientIp(request));flash.addFlashAttribute("verificationMessage",otp.recoveryMessage());}catch(IllegalArgumentException ex){flash.addFlashAttribute("verificationMessage",ex.getMessage());}return "redirect:/auth/verification";}
    @PostMapping("/auth/verification/reset") public String reset(@RequestParam String password,@RequestParam String confirmation,HttpServletRequest request,HttpServletResponse response,Authentication auth,RedirectAttributes flash){
        var s=request.getSession();try{otp.reset((String)s.getAttribute("ACCOUNT_OTP_FLOW"),s.getId(),password,confirmation,HttpRequestUtil.getClientIp(request));new SecurityContextLogoutHandler().logout(request,response,auth);flash.addFlashAttribute("successMessage","Đã đặt mật khẩu mới. Vui lòng đăng nhập.");return "redirect:/auth/login";}catch(RuntimeException ex){flash.addFlashAttribute("verificationMessage",ex instanceof IllegalArgumentException?ex.getMessage():"Không thể đặt mật khẩu. Vui lòng bắt đầu lại.");return "redirect:/auth/verification";}
    }
}
