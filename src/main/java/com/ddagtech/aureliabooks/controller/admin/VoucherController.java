package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.request.VoucherForm;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.entity.Voucher;
import com.ddagtech.aureliabooks.security.StaffIdentity;
import com.ddagtech.aureliabooks.service.VoucherService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.math.BigDecimal;
import java.time.*;

@Controller
@RequestMapping("/manager/vouchers")
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class VoucherController {
    private final VoucherService vouchers;
    private final StaffIdentity identity;
    private final Clock promotionClock;

    @GetMapping
    public String list(Authentication authentication, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("vouchers", vouchers.list(identity.resolve(authentication), keyword, active,
                PageRequest.of(Math.max(page, 0), 20, Sort.by("id").descending())));
        model.addAttribute("keyword", keyword);
        model.addAttribute("active", active);
        return "manager/vouchers/list";
    }

    @GetMapping("/new")
    public String form(Model model) {
        if (!model.containsAttribute("voucherForm")) {
            LocalDateTime now = LocalDateTime.now(promotionClock).withSecond(0).withNano(0);
            model.addAttribute("voucherForm", new VoucherForm("", "", Voucher.Scope.PRODUCT,
                    Voucher.Type.PERCENTAGE, BigDecimal.TEN, null, new BigDecimal("50000"),
                    BigDecimal.ZERO, now, now.plusDays(7), 100));
        }
        return "manager/vouchers/form";
    }

    @PostMapping
    public String create(Authentication authentication, @Valid @ModelAttribute("voucherForm") VoucherForm form,
            BindingResult errors, HttpServletRequest request, RedirectAttributes flash) {
        if (errors.hasErrors()) {
            return "manager/vouchers/form";
        }
        try {
            vouchers.create(identity.resolve(authentication), form, HttpRequestUtil.getClientIp(request));
        } catch (AppException ex) {
            if (ex.getErrorCode() != ErrorCode.VOUCHER_CODE_EXISTED && ex.getErrorCode() != ErrorCode.INVALID_INPUT_DATA) {
                throw ex;
            }
            errors.reject("voucher", ex.getMessage());
            return "manager/vouchers/form";
        }
        flash.addFlashAttribute("successMessage", "Đã tạo mã giảm giá.");
        return "redirect:/manager/vouchers";
    }

    @PostMapping("/{id}/status")
    public String status(Authentication authentication, @PathVariable Long id, @RequestParam boolean active,
            HttpServletRequest request, RedirectAttributes flash) {
        vouchers.setActive(identity.resolve(authentication), id, active, HttpRequestUtil.getClientIp(request));
        flash.addFlashAttribute("successMessage", active ? "Đã kích hoạt mã giảm giá." : "Đã tắt mã giảm giá.");
        return "redirect:/manager/vouchers";
    }
}
