package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.CartQuantityRequest;
import com.ddagtech.aureliabooks.dto.response.CartView;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Slf4j
@Controller
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService carts;
    private final CustomerIdentity identity;

    @GetMapping
    public ModelAndView view(Authentication authentication) {
        try {
            Long userId = identity.resolve(authentication);
            CartView cart = carts.viewCart(userId);

            return new ModelAndView("cart/view")
                    .addObject("cart", cart)
                    .addObject("cartItemCount", cart.totalQuantity())
                    .addObject("maxQuantity", CartQuantityRequest.MAX_QUANTITY);
        } catch (AppException error) {
            throw error;
        } catch (Exception error) {
            log.error("Unable to load cart", error);
            var page = new ModelAndView("cart/view");
            page.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
            page.addObject("cartLoadError", "Không thể tải giỏ hàng lúc này. Vui lòng thử lại.");
            return page;
        }
    }

    @PostMapping("/items")
    public String add(Authentication authentication, @RequestParam Long productId,
                      @Valid @ModelAttribute("quantityForm") CartQuantityRequest form,
                      BindingResult errors, RedirectAttributes flash) {
        Long owner = identity.resolve(authentication);
        if (invalidQuantity(errors, flash)) return "redirect:/cart";
        carts.addItem(owner, productId, form);
        flash.addFlashAttribute("successMessage", "Đã thêm sản phẩm vào giỏ hàng.");
        return "redirect:/cart";
    }

    @PostMapping("/items/{itemId}/quantity")
    public String update(Authentication authentication, @PathVariable Long itemId,
                         @Valid @ModelAttribute("quantityForm") CartQuantityRequest form,
                         BindingResult errors, RedirectAttributes flash) {
        Long owner = identity.resolve(authentication);
        if (invalidQuantity(errors, flash)) return "redirect:/cart";
        carts.updateQuantity(owner, itemId, form);
        flash.addFlashAttribute("successMessage", "Đã cập nhật số lượng sản phẩm.");
        return "redirect:/cart";
    }

    @PostMapping("/items/{itemId}/remove")
    public String remove(Authentication authentication, @PathVariable Long itemId, RedirectAttributes flash) {
        carts.removeItem(identity.resolve(authentication), itemId);
        flash.addFlashAttribute("successMessage", "Đã xóa sản phẩm khỏi giỏ hàng.");
        return "redirect:/cart";
    }

    @ExceptionHandler(AppException.class)
    public String businessError(AppException error, RedirectAttributes flash,
                                @SessionAttribute(name = "SPRING_SECURITY_CONTEXT", required = false)
                                SecurityContext sessionContext) {
        flash.addFlashAttribute("errorMessage", error.getMessage());
        ErrorCode code = error.getErrorCode();
        if (code == ErrorCode.UNAUTHENTICATED || code == ErrorCode.ACCOUNT_LOCKED
                || code == ErrorCode.USER_NOT_EXISTED) {
            // Clear authentication stored in the session and in the current thread.
            if (sessionContext != null) sessionContext.setAuthentication(null);
            SecurityContextHolder.clearContext();
            return "redirect:/auth/login";
        }
        if (code == ErrorCode.UNAUTHORIZED) return "redirect:/error/403";
        return "redirect:/cart";
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public String invalidRequest(RedirectAttributes flash) {
        flash.addFlashAttribute("errorMessage", "Thông tin sản phẩm hoặc số lượng chưa hợp lệ. Vui lòng thử lại.");
        return "redirect:/cart";
    }

    @ExceptionHandler(Exception.class)
    public String storageError(Exception error, RedirectAttributes flash) {
        log.error("Unable to save cart changes", error);
        flash.addFlashAttribute("errorMessage", "Không thể lưu thay đổi giỏ hàng. Vui lòng thử lại.");
        return "redirect:/cart";
    }

    private boolean invalidQuantity(BindingResult errors, RedirectAttributes flash) {
        if (!errors.hasErrors()) return false;
        var error = errors.getAllErrors().getFirst();
        flash.addFlashAttribute("errorMessage", error.getCode() != null && error.getCode().startsWith("typeMismatch")
                ? "Số lượng phải là số nguyên từ 1 đến 99." : error.getDefaultMessage());
        return true;
    }
}
