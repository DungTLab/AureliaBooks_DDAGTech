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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Custom AuthenticationEntryPoint managing HTTP 401 Unauthenticated scenarios.
 * Employs hybrid dispatching:
 * - For AJAX/REST API requests: Returns standardized JSON ApiResponse with status 401 (ErrorCode.UNAUTHENTICATED).
 * - For SSR browser navigation: Saves target request into session cache and redirects to /auth/login.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        String requestUri = request.getRequestURI();
        log.warn("Unauthenticated access attempt on protected URI '{}': {}", requestUri, authException.getMessage());

        if (HttpRequestUtil.isAjaxOrApi(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");

            ApiResponse<Object> apiResponse = ApiResponse.error(
                    ErrorCode.UNAUTHENTICATED.getCode(),
                    ErrorCode.UNAUTHENTICATED.getMessage()
            );

            objectMapper.writeValue(response.getWriter(), apiResponse);
        } else {
            // Save current request so that the user is returned after successful login
            requestCache.saveRequest(request, response);
            response.sendRedirect(request.getContextPath() + "/auth/login");
        }
    }
}
