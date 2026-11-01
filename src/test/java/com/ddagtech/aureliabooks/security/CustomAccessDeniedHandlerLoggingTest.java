package com.ddagtech.aureliabooks.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class CustomAccessDeniedHandlerLoggingTest {
    @Test void csrfFailureLogsTypeWithoutTokenValue(CapturedOutput output) throws Exception {
        var request=new MockHttpServletRequest("POST","/manager/products/images");
        request.addHeader("X-Requested-With","XMLHttpRequest");
        var response=new MockHttpServletResponse();
        new CustomAccessDeniedHandler(new ObjectMapper().findAndRegisterModules()).handle(request,response,
                new InvalidCsrfTokenException(new org.springframework.security.web.csrf.DefaultCsrfToken(
                        "X-CSRF-TOKEN","_csrf","qa-expected"),"qa-only-sensitive-csrf-value"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(output.getAll()).contains("InvalidCsrfTokenException").doesNotContain("qa-only-sensitive-csrf-value");
        assertThat(response.getContentAsString()).doesNotContain("qa-only-sensitive-csrf-value");
    }
}
