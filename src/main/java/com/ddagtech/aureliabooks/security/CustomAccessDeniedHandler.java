package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.ApiResponse;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Custom AccessDeniedHandler managing HTTP 403 Forbidden scenarios.
 * Employs hybrid dispatching:
 * - For CSRF violations: returns HTTP 403 status immediately with clear cause (missing/invalid token)
 *   via JSON for AJAX/API, or by forwarding to /error/403 with status 403 for browser form submissions.
 * - For RBAC authorization failures: returns JSON 403 for AJAX/API, or redirects (302) to /error/403 for SSR navigation.
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

        boolean isCsrfException = (accessDeniedException instanceof CsrfException);

        log.warn("Access Denied (403 Forbidden) [CSRF: {}] for user '{}' on URI '{}': {}",
                isCsrfException, username, requestUri, accessDeniedException.getMessage());

        if (isCsrfException) {
            String csrfErrorMessage;
            if (accessDeniedException instanceof MissingCsrfTokenException) {
                csrfErrorMessage = "Thiếu mã bảo vệ biểu mẫu. Vui lòng tải lại trang và thử lại.";
            } else if (accessDeniedException instanceof InvalidCsrfTokenException) {
                csrfErrorMessage = "Mã bảo vệ biểu mẫu không hợp lệ. Vui lòng tải lại trang và thử lại.";
            } else {
                csrfErrorMessage = "Không thể xác thực biểu mẫu. Vui lòng tải lại trang và thử lại.";
            }

            response.setStatus(HttpServletResponse.SC_FORBIDDEN);

            if (HttpRequestUtil.isAjaxOrApi(request)) {
                response.setContentType("application/json;charset=UTF-8");
                ApiResponse<Object> apiResponse = ApiResponse.error(
                        ErrorCode.UNAUTHORIZED.getCode(),
                        csrfErrorMessage
                );
                objectMapper.writeValue(response.getWriter(), apiResponse);
            } else {
                request.setAttribute("errorMessage", csrfErrorMessage);
                request.getRequestDispatcher("/error/403").forward(request, response);
            }
            return;
        }

        // Standard RBAC authorization failure
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
