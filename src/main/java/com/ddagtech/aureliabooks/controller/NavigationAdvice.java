package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.security.PortalSecurity;
import jakarta.servlet.http.HttpServletRequest;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.service.ProductService;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Resolves storefront links from current categories rather than seed identifiers. */
@ControllerAdvice
public class NavigationAdvice {

    private final ObjectProvider<ProductService> products;

    public NavigationAdvice(ObjectProvider<ProductService> products) {
        this.products = products;
    }

    @ModelAttribute("navigationCategories")
    public List<CategorySummary> categories(HttpServletRequest request) {
        if (PortalSecurity.internalRequest(request)) return List.of();
        ProductService service = products.getIfAvailable();
        return service == null ? List.of() : service.getActiveCategories();
    }
}
