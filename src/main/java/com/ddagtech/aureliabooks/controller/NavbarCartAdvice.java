package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.CartService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class NavbarCartAdvice {
    private final ObjectProvider<CartService> carts;
    private final ObjectProvider<CustomerIdentity> identities;

    @ModelAttribute("cartItemCount")
    public Long count(Authentication authentication, HttpServletRequest request) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            return 0L;
        }
        // The cart page supplies the count from the same snapshot as its displayed contents.
        if (request.getRequestURI().startsWith(request.getContextPath() + "/cart")) return null;
        var cartService = carts.getIfAvailable();
        var identity = identities.getIfAvailable();
        if (cartService == null || identity == null) return null;
        try {
            return cartService.countItems(identity.resolve(authentication));
        } catch (RuntimeException error) {
            // A count failure must not prevent catalog navigation or pretend the cart is empty.
            log.warn("Unable to load cart badge", error);
            return null;
        }
    }
}
