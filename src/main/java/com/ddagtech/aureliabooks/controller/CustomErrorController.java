package com.ddagtech.aureliabooks.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.stream.Collectors;

/**
 * Custom Error Controller serving branded, user-friendly SSR error pages (403, 404, 500).
 * Implements Spring Boot {@link ErrorController} to gracefully intercept unhandled HTTP errors.
 */
@Slf4j
@Controller
public class CustomErrorController implements ErrorController {

    private static final String ERROR_PATH = "/error";

    /**
     * Renders the 403 Forbidden Access Denied view.
     *
     * @param request HttpServletRequest
     * @param model   Spring MVC UI Model
     * @return Thymeleaf view path for error/403
     */
    @RequestMapping("/error/403")
    public String accessDenied(HttpServletRequest request, Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            model.addAttribute("username", auth.getName());
            String roles = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.joining(", "));
            model.addAttribute("roles", roles);
        } else {
            model.addAttribute("username", "Khách vãng lai");
            model.addAttribute("roles", "Chưa xác thực");
        }

        Object errorMessage = request.getAttribute("errorMessage");
        if (errorMessage != null) {
            model.addAttribute("errorMessage", errorMessage.toString());
        }

        model.addAttribute("title", "403 - Quyền Bị Từ Chối | AureliaBook");
        return "error/403";
    }

    /**
     * Renders the 404 Page Not Found view.
     *
     * @param model Spring MVC UI Model
     * @return Thymeleaf view path for error/404
     */
    @GetMapping("/error/404")
    public String notFound(Model model) {
        model.addAttribute("title", "404 - Không Tìm Thấy Trang | AureliaBook");
        return "error/404";
    }

    /**
     * Renders the 500 Internal Server Error view.
     *
     * @param model Spring MVC UI Model
     * @return Thymeleaf view path for error/500
     */
    @GetMapping("/error/500")
    public String internalServerError(Model model) {
        model.addAttribute("title", "500 - Lỗi Máy Chủ Nội Bộ | AureliaBook");
        return "error/500";
    }

    /**
     * Dispatches generic container errors to specific error templates based on status code.
     *
     * @param request HttpServletRequest containing container error attributes
     * @param model   Spring MVC UI Model
     * @return appropriate Thymeleaf error view path
     */
    @RequestMapping(ERROR_PATH)
    public String handleError(HttpServletRequest request, Model model) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);

        if (status != null) {
            int statusCode = Integer.parseInt(status.toString());
            log.warn("Handling HTTP container error status: {}", statusCode);

            if (statusCode == HttpStatus.FORBIDDEN.value()) {
                return accessDenied(request, model);
            } else if (statusCode == HttpStatus.NOT_FOUND.value()) {
                return notFound(model);
            }
        }

        return internalServerError(model);
    }
}
