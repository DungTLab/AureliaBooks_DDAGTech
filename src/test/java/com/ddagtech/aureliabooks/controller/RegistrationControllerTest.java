package com.ddagtech.aureliabooks.controller;
import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.service.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import java.nio.file.*;
import java.time.LocalDate;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest(AuthController.class) @Import({SecurityConfig.class,
        com.ddagtech.aureliabooks.security.CustomAccessDeniedHandler.class})
class RegistrationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RegistrationService registration;
    @MockitoBean com.ddagtech.aureliabooks.service.AccountOtpService otp;
    MockHttpServletRequestBuilder form() {
        return post("/auth/register").with(csrf()).param("email","a@example.com").param("fullName","Nguyễn Văn Dũng")
                .param("phone","0912345678").param("password","Abcd123!").param("confirmPassword","Abcd123!")
                .param("agreeTerms","true");
    }
    @Test void formRendersWithCsrfAndNoRoleInput() throws Exception {
        var result=mvc.perform(get("/auth/register")).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("name=\"role"))))
                .andExpect(content().string(containsString("Đăng ký"))).andReturn();
        Path dir=Path.of("docs/registration"); Files.createDirectories(dir);
        Files.writeString(dir.resolve("rendered-register.html"),result.getResponse().getContentAsString());
    }
    @Test void validFormRedirectsAndCannotAssignAdmin() throws Exception {
        when(otp.registration(any(),anyString(),anyString())).thenReturn("flow");
        mvc.perform(form().param("role","ROLE_ADMIN").param("dob",LocalDate.now().minusDays(1).toString()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/verification"))
                .andExpect(flash().attributeExists("verificationMessage"));
        verify(otp).registration(argThat(r->r.email()==null&&r.gender()!=null && r.dob().equals(LocalDate.now().minusDays(1))),anyString(),anyString());
        verifyNoInteractions(registration);
    }
    @Test void missingCsrfNeverReachesRegistration() throws Exception {
        mvc.perform(post("/auth/register")).andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/error/403"))
                .andExpect(request().attribute("errorMessage", "Thiếu mã bảo vệ biểu mẫu. Vui lòng tải lại trang và thử lại."));
        verifyNoInteractions(registration);
    }
    @Test void invalidFormDisplaysVietnameseErrorsWithoutEchoingPassword() throws Exception {
        mvc.perform(form().param("confirmPassword","Mismatch!"))
                .andExpect(status().isOk()).andExpect(model().attributeHasErrors("registerRequest"))
                .andExpect(content().string(containsString("Mật khẩu xác nhận chưa khớp.")))
                .andExpect(content().string(not(containsString("value=\"Abcd123!\""))));
        verifyNoInteractions(registration);
    }
    @Test void invalidCalendarDateShowsFriendlyError() throws Exception {
        mvc.perform(form().param("dob","2026-02-30")).andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("registerRequest","dob"))
                .andExpect(content().string(containsString("Ngày sinh không hợp lệ.")));
        verifyNoInteractions(registration);
    }
    @Test void dayMonthYearIsParsedWithoutSwappingAndInvalidCalendarDatesAreRejected() throws Exception {
        when(otp.registration(any(), anyString(), anyString())).thenReturn("flow");
        mvc.perform(form().param("dob", "31/12/2000")).andExpect(redirectedUrl("/auth/verification"));
        verify(otp).registration(argThat(request -> LocalDate.of(2000, 12, 31).equals(request.dob())), anyString(), anyString());
        clearInvocations(otp);
        for (String invalid : new String[]{"31/02/2000", "29/02/2023", "12/31/2000"}) {
            mvc.perform(form().param("dob", invalid)).andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("registerRequest", "dob"));
        }
        verifyNoInteractions(otp);
    }
    @Test void concurrentDuplicateConstraintIsTranslatedWithoutSqlLeak() throws Exception {
        when(otp.registration(any(),anyString(),anyString())).thenThrow(new DataIntegrityViolationException("SQL secret"));
        mvc.perform(form()).andExpect(status().isOk())
                .andExpect(content().string(containsString("Email hoặc số điện thoại này đã được đăng ký.")))
                .andExpect(content().string(not(containsString("SQL secret"))));
    }
}
