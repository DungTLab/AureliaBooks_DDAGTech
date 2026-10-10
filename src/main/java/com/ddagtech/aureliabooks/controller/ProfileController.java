package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller @RequiredArgsConstructor
public class ProfileController {
    private final UserService users;
    private final AddressService addresses;
    private final CustomerIdentity identity;
    private final AvatarStorage avatars;

    @GetMapping("/profile")
    public String profile(Authentication auth, Model model) {
        var profile = users.viewProfile(identity.resolve(auth)); model.addAttribute("profile", profile);
        if (!model.containsAttribute("profileForm")) model.addAttribute("profileForm",
                new ProfileUpdateRequest(profile.fullName(), null, null, User.Gender.valueOf(profile.gender())));
        model.addAttribute("passwordForm", new PasswordChangeRequest(null, null, null));
        return "profile/view";
    }
    @PostMapping("/profile")
    public String update(Authentication auth, @Valid @ModelAttribute("profileForm") ProfileUpdateRequest form,
                         BindingResult errors, RedirectAttributes flash) {
        Long owner = identity.resolve(auth);
        if (errors.hasErrors()) { feedback(errors, flash); flash.addFlashAttribute("profileForm", form); }
        else {
            try { users.updateProfile(owner, form); flash.addFlashAttribute("successMessage", "Đã cập nhật thông tin cá nhân."); }
            catch (DataIntegrityViolationException e) { flash.addFlashAttribute("errorMessage", "Số điện thoại này đã được đăng ký."); }
        }
        return "redirect:/profile";
    }
    @PostMapping("/profile/password")
    public String password(Authentication auth, @Valid @ModelAttribute("passwordForm") PasswordChangeRequest form,
            BindingResult errors, RedirectAttributes flash, HttpServletRequest request, HttpServletResponse response) {
        Long owner = identity.resolve(auth);
        if (errors.hasErrors()) { feedback(errors, flash); return "redirect:/profile"; }
        users.changePassword(owner, form);
        new SecurityContextLogoutHandler().logout(request, response, auth);
        Cookie cookie = new Cookie("JSESSIONID", ""); cookie.setPath("/");
        cookie.setHttpOnly(true); cookie.setMaxAge(0); response.addCookie(cookie);
        return "redirect:/auth/login?passwordChanged";
    }
    @PostMapping("/profile/avatar")
    public String avatar(Authentication auth, @RequestParam("avatar") MultipartFile file, RedirectAttributes flash) {
        users.updateAvatar(identity.resolve(auth), file);
        flash.addFlashAttribute("successMessage", "Đã cập nhật ảnh đại diện."); return "redirect:/profile";
    }
    @GetMapping("/profile/avatar/{filename}")
    public ResponseEntity<byte[]> avatarFile(Authentication auth, @PathVariable String filename) {
        var profile = users.viewProfile(identity.resolve(auth));
        if (!("/profile/avatar/" + filename).equals(profile.avatarUrl())) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff").body(avatars.read(filename));
    }
    @GetMapping("/profile/addresses")
    public String addresses(Authentication auth, @RequestParam(required = false) Long edit, Model model) {
        var list = addresses.list(identity.resolve(auth)); model.addAttribute("addresses", list);
        if (!model.containsAttribute("addressForm")) {
            var selected = list.stream().filter(a -> a.id().equals(edit)).findFirst();
            if (edit != null && selected.isEmpty()) throw new AppException(com.ddagtech.aureliabooks.constant.ErrorCode.ADDRESS_NOT_FOUND);
            model.addAttribute("addressForm", selected.map(a -> new AddressRequest(a.recipientName(), a.phone(),
                    a.economicRegion(), a.province(), a.district(), a.ward(), a.detailedAddress(), a.isDefault()))
                    .orElse(new AddressRequest(null, null, null, null, null, null, null, false)));
        }
        model.addAttribute("editId", edit); return "profile/addresses";
    }
    @PostMapping("/profile/addresses")
    public String saveAddress(Authentication auth, @RequestParam(required = false) Long addressId,
            @Valid @ModelAttribute("addressForm") AddressRequest form, BindingResult errors, RedirectAttributes flash) {
        Long owner = identity.resolve(auth);
        if (errors.hasErrors()) {
            feedback(errors, flash); flash.addFlashAttribute("addressForm", form);
            return "redirect:/profile/addresses" + (addressId == null ? "" : "?edit=" + addressId);
        }
        if (addressId == null) addresses.create(owner, form); else addresses.update(owner, addressId, form);
        flash.addFlashAttribute("successMessage", "Đã lưu địa chỉ giao hàng."); return "redirect:/profile/addresses";
    }
    @PostMapping("/profile/addresses/{id}/default")
    public String setDefault(Authentication auth, @PathVariable Long id, RedirectAttributes flash) {
        addresses.setDefault(identity.resolve(auth), id);
        flash.addFlashAttribute("successMessage", "Đã chọn địa chỉ mặc định."); return "redirect:/profile/addresses";
    }
    @PostMapping("/profile/addresses/{id}/delete")
    public String delete(Authentication auth, @PathVariable Long id, RedirectAttributes flash) {
        addresses.delete(identity.resolve(auth), id);
        flash.addFlashAttribute("successMessage", "Đã xóa địa chỉ giao hàng."); return "redirect:/profile/addresses";
    }
    @ExceptionHandler(AppException.class)
    public String businessError(AppException e, HttpServletRequest request, HttpServletResponse response, RedirectAttributes flash) {
        flash.addFlashAttribute("errorMessage", e.getMessage());
        if (e.getErrorCode() == com.ddagtech.aureliabooks.constant.ErrorCode.UNAUTHORIZED) return "redirect:/error/403";
        if (e.getErrorCode() == com.ddagtech.aureliabooks.constant.ErrorCode.UNAUTHENTICATED
                || e.getErrorCode() == com.ddagtech.aureliabooks.constant.ErrorCode.ACCOUNT_LOCKED) {
            new SecurityContextLogoutHandler().logout(request, response,
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
            return "redirect:/auth/login";
        }
        return request.getRequestURI().startsWith("/profile/addresses") ? "redirect:/profile/addresses" : "redirect:/profile";
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String uploadError(RedirectAttributes flash) {
        flash.addFlashAttribute("errorMessage", "Ảnh đại diện không được vượt quá 2 MB."); return "redirect:/profile";
    }
    private void feedback(BindingResult errors, RedirectAttributes flash) {
        var first = errors.getAllErrors().get(0);
        flash.addFlashAttribute("errorMessage", first.getCode() != null && first.getCode().startsWith("typeMismatch")
                ? "Thông tin nhập không hợp lệ. Vui lòng kiểm tra lại." : first.getDefaultMessage());
    }
}
