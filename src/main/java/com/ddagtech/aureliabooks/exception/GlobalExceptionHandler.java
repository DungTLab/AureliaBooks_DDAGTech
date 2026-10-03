package com.ddagtech.aureliabooks.exception;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.ApiResponse;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized Hybrid Global Exception Handler.
 * Supports both Monolithic SSR views (redirecting with flash alerts)
 * and REST API/AJAX requests (returning standardized ApiResponse JSON).
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles custom business domain exceptions (AppException).
     *
     * @param exception          the domain exception
     * @param request            the current HTTP request
     * @param redirectAttributes redirect attributes for flash alerts
     * @return ResponseEntity with JSON for API or redirect view for SSR
     */
    @ExceptionHandler(AppException.class)
    public Object handleAppException(AppException exception,
                                     HttpServletRequest request,
                                     RedirectAttributes redirectAttributes) {
        ErrorCode errorCode = exception.getErrorCode();
        log.warn("AppException caught [Code: {}]: {}", errorCode.getCode(), exception.getMessage());

        if (HttpRequestUtil.isAjaxOrApi(request)) {
            ApiResponse<Object> response = ApiResponse.error(errorCode.getCode(), exception.getMessage());
            return ResponseEntity.status(errorCode.getStatusCode()).body(response);
        }

        redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        return getSafeRedirectUrl(request);
    }

    /**
     * Handles validation errors from @Valid annotated request bodies or form models.
     *
     * @param exception          validation failure exception
     * @param request            the current HTTP request
     * @param redirectAttributes redirect attributes for flash alerts
     * @return ResponseEntity with JSON field errors for API or redirect view for SSR
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Object handleValidationException(Exception exception,
                                            HttpServletRequest request,
                                            RedirectAttributes redirectAttributes) {
        log.warn("Validation failure caught: {}", exception.getMessage());

        Map<String, String> fieldErrors = new HashMap<>();
        String firstErrorMessage = ErrorCode.INVALID_INPUT_DATA.getMessage();

        if (exception instanceof MethodArgumentNotValidException manve) {
            for (FieldError fieldError : manve.getBindingResult().getFieldErrors()) {
                fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
            }
            if (!manve.getBindingResult().getFieldErrors().isEmpty()) {
                firstErrorMessage = manve.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
            }
        } else if (exception instanceof BindException be) {
            for (FieldError fieldError : be.getBindingResult().getFieldErrors()) {
                fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
            }
            if (!be.getBindingResult().getFieldErrors().isEmpty()) {
                firstErrorMessage = be.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
            }
        }

        if (HttpRequestUtil.isAjaxOrApi(request)) {
            ApiResponse<Map<String, String>> response = ApiResponse.<Map<String, String>>builder()
                    .code(ErrorCode.INVALID_INPUT_DATA.getCode())
                    .message(ErrorCode.INVALID_INPUT_DATA.getMessage())
                    .result(fieldErrors)
                    .build();
            return ResponseEntity.status(ErrorCode.INVALID_INPUT_DATA.getStatusCode()).body(response);
        }

        redirectAttributes.addFlashAttribute("errorMessage", firstErrorMessage);
        return getSafeRedirectUrl(request);
    }

    /**
     * Handles AccessDeniedException thrown by method security or URL evaluation.
     *
     * @param exception AccessDeniedException instance
     * @param request   the current HTTP request
     * @return ResponseEntity with JSON for API or redirect to /error/403 for SSR
     */
    @ExceptionHandler(AccessDeniedException.class)
    public Object handleAccessDeniedException(AccessDeniedException exception, HttpServletRequest request) {
        log.warn("AccessDeniedException caught: {}", exception.getMessage());

        if (HttpRequestUtil.isAjaxOrApi(request)) {
            ApiResponse<Object> response = ApiResponse.error(
                    ErrorCode.UNAUTHORIZED.getCode(),
                    ErrorCode.UNAUTHORIZED.getMessage()
            );
            return ResponseEntity.status(ErrorCode.UNAUTHORIZED.getStatusCode()).body(response);
        }

        return "redirect:/error/403";
    }

    /**
     * Handles NoResourceFoundException thrown when a requested URL path or static resource does not exist.
     *
     * @param exception NoResourceFoundException instance
     * @param request   the current HTTP request
     * @return ResponseEntity with JSON for API or redirect to /error/404 for SSR
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public Object handleNoResourceFoundException(org.springframework.web.servlet.resource.NoResourceFoundException exception,
                                                 HttpServletRequest request) {
        log.warn("Resource not found caught: {}", exception.getMessage());

        if (HttpRequestUtil.isAjaxOrApi(request)) {
            ApiResponse<Object> response = ApiResponse.error(
                    ErrorCode.RESOURCE_NOT_FOUND.getCode(),
                    ErrorCode.RESOURCE_NOT_FOUND.getMessage()
            );
            return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getStatusCode()).body(response);
        }

        return "redirect:/error/404";
    }

    /**
     * Handles AuthenticationException thrown when unauthenticated user attempts access.
     *
     * @param exception AuthenticationException instance
     * @param request   the current HTTP request
     * @return ResponseEntity with JSON for API or redirect to /auth/login for SSR
     */
    @ExceptionHandler(AuthenticationException.class)
    public Object handleAuthenticationException(AuthenticationException exception, HttpServletRequest request) {
        log.warn("AuthenticationException caught: {}", exception.getMessage());

        if (HttpRequestUtil.isAjaxOrApi(request)) {
            ApiResponse<Object> response = ApiResponse.error(
                    ErrorCode.UNAUTHENTICATED.getCode(),
                    ErrorCode.UNAUTHENTICATED.getMessage()
            );
            return ResponseEntity.status(ErrorCode.UNAUTHENTICATED.getStatusCode()).body(response);
        }

        return "redirect:/auth/login";
    }

    /**
     * Fallback handler for all unexpected or uncategorized exceptions.
     *
     * @param exception the unhandled exception
     * @param request   the current HTTP request
     * @return ResponseEntity with JSON for API or redirect to /error/500 for SSR
     */
    @ExceptionHandler(Exception.class)
    public Object handleGenericException(Exception exception, HttpServletRequest request) {
        log.error("Unhandled Exception caught: ", exception);

        if (HttpRequestUtil.isAjaxOrApi(request)) {
            ApiResponse<Object> response = ApiResponse.error(
                    ErrorCode.UNCATEGORIZED_EXCEPTION.getCode(),
                    ErrorCode.UNCATEGORIZED_EXCEPTION.getMessage()
            );
            return ResponseEntity.status(ErrorCode.UNCATEGORIZED_EXCEPTION.getStatusCode()).body(response);
        }

        return "redirect:/error/500";
    }

    /**
     * Resolves a safe redirect destination from the HTTP Referer header to prevent open redirect vulnerabilities (CWE-601).
     * Only permits same-origin absolute URLs or relative paths, discarding external domains and dangerous protocols.
     *
     * @param request incoming HttpServletRequest
     * @return safe redirect view string
     */
    private String getSafeRedirectUrl(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            try {
                URI uri = URI.create(referer.trim());
                // Relative URL (e.g. /cart) - reject protocol-relative (//external.com)
                if (!uri.isAbsolute()) {
                    if (referer.startsWith("/") && !referer.startsWith("//")
                            && !referer.contains("/error") && !referer.contains("/auth/login")) {
                        return "redirect:" + referer;
                    }
                } else {
                    // Absolute URL - verify host matches the current request
                    String requestHost = request.getServerName();
                    String refererHost = uri.getHost();
                    if (requestHost != null && requestHost.equalsIgnoreCase(refererHost)) {
                        String path = uri.getRawPath();
                        String query = uri.getRawQuery();
                        if (path != null && path.startsWith("/") && !path.startsWith("//")
                                && !path.contains("/error") && !path.contains("/auth/login")) {
                            return "redirect:" + path + (query != null ? "?" + query : "");
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Malformed Referer header ignored: {}", referer);
            }
        }
        return "redirect:/";
    }
}

