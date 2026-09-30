package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.ApiResponse;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Custom AccessDeniedHandler managing HTTP 403 Forbidden scenarios.
 * Employs hybrid dispatching:
 * - For AJAX/REST API requests: Returns standardized JSON ApiResponse with status 403.
 * - For SSR browser navigation: Redirects to the friendly /error/403 Thymeleaf view.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null) ? auth.getName() : "Anonymous";
        String requestUri = request.getRequestURI();

        log.warn("Access Denied (403 Forbidden) for user '{}' on URI '{}': {}",
                username, requestUri, accessDeniedException.getMessage());

        if (HttpRequestUtil.isAjaxOrApi(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");

            ApiResponse<Object> apiResponse = ApiResponse.error(
                    ErrorCode.UNAUTHORIZED.getCode(),
                    ErrorCode.UNAUTHORIZED.getMessage()
            );

            objectMapper.writeValue(response.getWriter(), apiResponse);
        } else {
            response.sendRedirect(request.getContextPath() + "/error/403");
        }
    }
}
