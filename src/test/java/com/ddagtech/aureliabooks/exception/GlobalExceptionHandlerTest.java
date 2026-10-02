package com.ddagtech.aureliabooks.exception;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test suite for {@link GlobalExceptionHandler}.
 * Validates hybrid exception handling (JSON ApiResponse for REST/AJAX vs redirect/views for SSR).
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @InjectMocks
    private GlobalExceptionHandler exceptionHandler;

    private MockHttpServletRequest request;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        redirectAttributes = new RedirectAttributesModelMap();
    }

    @Test
    @DisplayName("Should return JSON ApiResponse when AppException occurs during an AJAX request")
    void testHandleAppException_WhenAjax_ReturnsJsonApiResponse() {
        request.addHeader("Accept", "application/json");

        AppException appException = new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Book not found");
        Object result = exceptionHandler.handleAppException(appException, request, redirectAttributes);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<ApiResponse<Object>> responseEntity = (ResponseEntity<ApiResponse<Object>>) result;

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(responseEntity.getBody()).isNotNull();
        assertThat(responseEntity.getBody().getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode());
        assertThat(responseEntity.getBody().getMessage()).isEqualTo("Book not found");
    }

    @Test
    @DisplayName("Should return safe redirect with flash alert when AppException occurs during SSR navigation")
    void testHandleAppException_WhenHtml_ReturnsRedirectWithFlashAlert() {
        request.addHeader("Accept", "text/html");
        request.addHeader("Referer", "http://localhost:8080/cart");

        AppException appException = new AppException(ErrorCode.ADDRESS_QUOTA_EXCEEDED);
        Object result = exceptionHandler.handleAppException(appException, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:http://localhost:8080/cart");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo(ErrorCode.ADDRESS_QUOTA_EXCEEDED.getMessage());
    }

    @Test
    @DisplayName("Should return 403 JSON ApiResponse when AccessDeniedException occurs during AJAX request")
    void testHandleAccessDeniedException_WhenAjax_ReturnsJsonForbidden() {
        request.addHeader("Accept", "application/json");

        AccessDeniedException accessDeniedException = new AccessDeniedException("Access is denied");
        Object result = exceptionHandler.handleAccessDeniedException(accessDeniedException, request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<ApiResponse<Object>> responseEntity = (ResponseEntity<ApiResponse<Object>>) result;

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(responseEntity.getBody()).isNotNull();
        assertThat(responseEntity.getBody().getCode()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("Should redirect to /error/403 when AccessDeniedException occurs during SSR navigation")
    void testHandleAccessDeniedException_WhenHtml_RedirectsTo403View() {
        request.addHeader("Accept", "text/html");

        AccessDeniedException accessDeniedException = new AccessDeniedException("Access is denied");
        Object result = exceptionHandler.handleAccessDeniedException(accessDeniedException, request);

        assertThat(result).isEqualTo("redirect:/error/403");
    }

    @Test
    @DisplayName("Should redirect to /error/500 when uncategorized Exception occurs during SSR navigation")
    void testHandleGenericException_WhenHtml_RedirectsTo500View() {
        request.addHeader("Accept", "text/html");

        Exception genericException = new RuntimeException("Unexpected database outage");
        Object result = exceptionHandler.handleGenericException(genericException, request);

        assertThat(result).isEqualTo("redirect:/error/500");
    }
}
