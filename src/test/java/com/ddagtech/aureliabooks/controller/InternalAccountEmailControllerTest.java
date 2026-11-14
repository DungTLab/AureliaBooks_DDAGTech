package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.AdminStaffEmailChallengeView;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.security.AuthenticatedSessionUpdater;
import com.ddagtech.aureliabooks.security.StaffIdentity;
import com.ddagtech.aureliabooks.service.AccountSecurityService;
import com.ddagtech.aureliabooks.service.StaffContactService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = InternalAccountEmailController.class)
@Import(SecurityConfig.class)
class InternalAccountEmailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StaffIdentity staffIdentity;

    @MockitoBean
    private AccountSecurityService accountSecurityService;

    @MockitoBean
    private StaffContactService staffContactService;

    @MockitoBean
    private AuthenticatedSessionUpdater sessionUpdater;

    private User staffUser;

    @BeforeEach
    void setUp() {
        staffUser = User.builder()
                .id(15L)
                .email("staff@aureliabook.vn")
                .fullName("Nguyen Staff")
                .role(Role.builder().id(3L).roleName("ROLE_SALE_STAFF").build())
                .isActive(true)
                .emailVerified(false)
                .build();
        when(staffIdentity.resolve(any())).thenReturn(15L);
        when(staffContactService.loadInternalStaff(15L)).thenReturn(staffUser);
        when(staffContactService.findSelfEmailChallenge(eq(15L), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Anonymous visitor to /internal/account/email is redirected to /internal/login")
    void anonymousRedirectedToInternalLogin() throws Exception {
        mockMvc.perform(get("/internal/account/email"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/internal/login"));
    }

    @Test
    @DisplayName("CUSTOMER role is forbidden from /internal/account/email endpoints")
    void customerIsForbidden() throws Exception {
        mockMvc.perform(get("/internal/account/email").with(user("cust").roles("CUSTOMER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/internal/account/email/reauth")
                        .with(user("cust").roles("CUSTOMER"))
                        .with(csrf())
                        .param("currentPassword", "Secret123@"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(accountSecurityService, staffContactService);
    }

    @Test
    @DisplayName("Staff sees recovery email page and cannot bypass password re-authentication before starting OTP")
    void startWithoutReauthIsRejected() throws Exception {
        mockMvc.perform(get("/internal/account/email").with(user("staff").roles("SALE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(view().name("internal/recovery-email"))
                .andExpect(model().attribute("reauthenticated", false))
                .andExpect(content().string(containsString("data-portal=\"internal\"")))
                .andExpect(content().string(containsString("/internal/account/email/reauth")));

        mockMvc.perform(post("/internal/account/email/start")
                        .with(user("staff").roles("SALE_STAFF"))
                        .with(csrf())
                        .param("email", "staff@aureliabook.vn"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/account/email"))
                .andExpect(flash().attributeExists("errorMessage"));

        verify(staffContactService, never()).startSelfEmailVerification(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Full flow: reauth -> start OTP -> resend -> verify updates email and refreshes internal session")
    void fullSelfVerificationFlow() throws Exception {
        MockHttpSession session = new MockHttpSession();
        doNothing().when(accountSecurityService).reauthenticateStaff(15L, "CurrentPass@123");

        mockMvc.perform(post("/internal/account/email/reauth")
                        .session(session)
                        .with(user("staff").roles("MANAGER"))
                        .with(csrf())
                        .param("currentPassword", "CurrentPass@123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/account/email"))
                .andExpect(flash().attributeExists("successMessage"));

        when(staffContactService.startSelfEmailVerification(eq(15L), anyString(), eq("work.new@aureliabook.vn"), any()))
                .thenReturn(new AdminStaffEmailChallengeView(15L, "w***@aureliabook.vn", 300L, 60L));

        mockMvc.perform(post("/internal/account/email/start")
                        .session(session)
                        .with(user("staff").roles("MANAGER"))
                        .with(csrf())
                        .param("email", "work.new@aureliabook.vn"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/account/email"))
                .andExpect(flash().attributeExists("successMessage"));

        User verifiedUser = User.builder()
                .id(15L)
                .email("work.new@aureliabook.vn")
                .emailVerified(true)
                .role(Role.builder().id(2L).roleName("ROLE_MANAGER").build())
                .isActive(true)
                .build();
        when(staffContactService.verifySelfEmail(eq(15L), anyString(), eq("123456"), any()))
                .thenReturn(verifiedUser);

        mockMvc.perform(post("/internal/account/email/verify")
                        .session(session)
                        .with(user("staff").roles("MANAGER"))
                        .with(csrf())
                        .param("code", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/account/email"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(sessionUpdater).refreshStaff(eq(verifiedUser), any(), any());
    }

    @Test
    @DisplayName("Wrong reauth password and OTP exceptions flash clear Vietnamese errors")
    void errorHandlingDuringReauthAndOtp() throws Exception {
        doThrow(new AppException(ErrorCode.PASSWORD_NOT_MATCH))
                .when(accountSecurityService).reauthenticateStaff(15L, "WrongPass!");

        mockMvc.perform(post("/internal/account/email/reauth")
                        .with(user("staff").roles("ADMIN"))
                        .with(csrf())
                        .param("currentPassword", "WrongPass!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/account/email"))
                .andExpect(flash().attribute("errorMessage", ErrorCode.PASSWORD_NOT_MATCH.getMessage()));

        doThrow(new AppException(ErrorCode.OTP_INVALID_OR_EXPIRED))
                .when(staffContactService).verifySelfEmail(eq(15L), anyString(), eq("000000"), any());

        mockMvc.perform(post("/internal/account/email/verify")
                        .with(user("staff").roles("ADMIN"))
                        .with(csrf())
                        .param("code", "000000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/account/email"))
                .andExpect(flash().attribute("errorMessage", ErrorCode.OTP_INVALID_OR_EXPIRED.getMessage()));
    }

    @Test
    @DisplayName("Verified recovery-email page hides /internal/forgot-password eligibility notice for ROLE_ADMIN while keeping it for MANAGER/SALE_STAFF")
    void recoveryEmailViewDistinguishesAdminFromStaffRecoveryEligibility() throws Exception {
        User verifiedManager = User.builder()
                .id(15L)
                .email("manager@aureliabook.vn")
                .role(Role.builder().id(2L).roleName("ROLE_MANAGER").build())
                .isActive(true)
                .emailVerified(true)
                .build();
        when(staffContactService.loadInternalStaff(15L)).thenReturn(verifiedManager);
        when(staffContactService.hasVerifiedEmail(verifiedManager)).thenReturn(true);

        mockMvc.perform(get("/internal/account/email").with(user("mgr").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tài khoản đã đủ điều kiện dùng chức năng")));

        User verifiedAdmin = User.builder()
                .id(15L)
                .email("admin@aureliabook.vn")
                .role(Role.builder().id(1L).roleName("ROLE_ADMIN").build())
                .isActive(true)
                .emailVerified(true)
                .build();
        when(staffContactService.loadInternalStaff(15L)).thenReturn(verifiedAdmin);
        when(staffContactService.hasVerifiedEmail(verifiedAdmin)).thenReturn(true);

        mockMvc.perform(get("/internal/account/email").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Tài khoản đã đủ điều kiện dùng chức năng"))))
                .andExpect(content().string(containsString("không áp dụng tự đặt lại mật khẩu")));

        when(staffContactService.verifySelfEmail(eq(15L), anyString(), eq("654321"), any())).thenReturn(verifiedAdmin);
        mockMvc.perform(post("/internal/account/email/verify")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("code", "654321"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/account/email"))
                .andExpect(flash().attribute("successMessage", "Xác minh email công việc thành công."));
    }
}
