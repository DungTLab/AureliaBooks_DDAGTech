package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.dto.response.UserSummary;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web MVC tests for {@link AdminUserController} (UC28).
 * Verifies role-based access control (RBAC), Post-Redirect-Get (PRG) patterns,
 * form validation handling, and form state preservation on business rule errors.
 */
@WebMvcTest(controllers = AdminUserController.class)
@Import({SecurityConfig.class, com.ddagtech.aureliabooks.security.CustomAccessDeniedHandler.class})
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUserService adminUserService;

    @MockitoBean
    private RoleRepository roleRepository;

    private CustomUserDetails adminUserDetails;
    private CustomUserDetails staffUserDetails;
    private Role adminRole;
    private Role managerRole;
    private Role staffRole;

    @BeforeEach
    void setUp() {
        adminRole = Role.builder().id(1L).roleName("ROLE_ADMIN").description("Administrator").build();
        managerRole = Role.builder().id(2L).roleName("ROLE_MANAGER").description("Store Manager").build();
        staffRole = Role.builder().id(3L).roleName("ROLE_SALE_STAFF").description("Sales Staff").build();

        User adminEntity = User.builder()
                .id(1L)
                .email("admin@aureliabook.vn")
                .fullName("Administrator")
                .role(adminRole)
                .isActive(true)
                .build();
        adminUserDetails = new CustomUserDetails(adminEntity);

        User staffEntity = User.builder()
                .id(2L)
                .email("staff@aureliabook.vn")
                .fullName("Staff User")
                .role(staffRole)
                .isActive(true)
                .build();
        staffUserDetails = new CustomUserDetails(staffEntity);

        when(adminUserService.listAssignableRoles()).thenReturn(List.of(adminRole, managerRole, staffRole).stream()
                .map(role -> new com.ddagtech.aureliabooks.dto.response.RoleOption(role.getId(), role.getRoleName(), role.getDescription())).toList());
    }

    @Test
    @DisplayName("GET /admin/users with authenticated ADMIN returns list view and model")
    void testList_AuthenticatedAdmin_Success() throws Exception {
        UserSummary summary = new UserSummary(
                10L,
                "staff10@aureliabook.vn",
                "Nguyen Van A",
                "0987654321",
                3L,
                "ROLE_SALE_STAFF",
                true,
                LocalDateTime.now()
        );
        Page<UserSummary> page = new PageImpl<>(List.of(summary));
        when(adminUserService.list(isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/admin/users").with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users/list"))
                .andExpect(model().attributeExists("usersPage"))
                .andExpect(model().attributeExists("roles"))
                .andExpect(model().attributeExists("newUserForm"));
    }

    @Test
    @DisplayName("GET /admin/users unauthenticated redirects to login")
    void testList_Unauthenticated_RedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/internal/login"));
    }

    @Test
    @DisplayName("GET /admin/users with non-ADMIN principal redirects to /error/403 via CustomAccessDeniedHandler")
    void testList_NonAdmin_Forbidden() throws Exception {
        mockMvc.perform(get("/admin/users").with(user(staffUserDetails)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));
    }

    @Test
    @DisplayName("POST /admin/users/create with valid data provisions user and redirects with success message")
    void testCreate_ValidData_Success() throws Exception {
        when(adminUserService.create(eq(1L), any(UserCreateRequest.class), any())).thenReturn(99L);

        mockMvc.perform(post("/admin/users/create")
                        .with(csrf())
                        .with(user(adminUserDetails))
                        .param("fullName", "Nguyen Van B")
                        .param("email", "staff_b@aureliabook.vn")
                        .param("phone", "0987654321")
                        .param("roleId", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(adminUserService).create(eq(1L), any(UserCreateRequest.class), any());
    }

    @Test
    @DisplayName("POST /admin/users/create with validation errors redirects with form state and openCreateModal flag")
    void testCreate_ValidationError_PreservesFormState() throws Exception {
        mockMvc.perform(post("/admin/users/create")
                        .with(csrf())
                        .with(user(adminUserDetails))
                        .param("fullName", "")
                        .param("email", "not-an-email")
                        .param("phone", "123")
                        .param("roleId", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(flash().attributeExists("newUserForm"))
                .andExpect(flash().attribute("openCreateModal", true));

        verifyNoInteractions(adminUserService);
    }

    @Test
    @DisplayName("POST /admin/users/create when email exists catches AppException, preserves form state and sets openCreateModal")
    void testCreate_DuplicateEmail_PreservesFormState() throws Exception {
        when(adminUserService.create(eq(1L), any(UserCreateRequest.class), any()))
                .thenThrow(new AppException(ErrorCode.USER_EXISTED, "Email đã được sử dụng trong hệ thống"));

        mockMvc.perform(post("/admin/users/create")
                        .with(csrf())
                        .with(user(adminUserDetails))
                        .param("fullName", "Nguyen Van B")
                        .param("email", "existing@aureliabook.vn")
                        .param("phone", "0987654321")
                        .param("roleId", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("errorMessage", "Email đã được sử dụng trong hệ thống"))
                .andExpect(flash().attributeExists("newUserForm"))
                .andExpect(flash().attribute("openCreateModal", true));

        verify(adminUserService).create(eq(1L), any(UserCreateRequest.class), any());
    }

    @Test
    @DisplayName("POST /admin/users/{id}/toggle-status invokes service and redirects with success message")
    void testToggleStatus_Success() throws Exception {
        mockMvc.perform(post("/admin/users/10/toggle-status")
                        .with(csrf())
                        .with(user(adminUserDetails))
                        .param("active", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("successMessage", "Khóa tài khoản thành công!"));

        verify(adminUserService).setActive(eq(1L), eq(10L), eq(false), any());
    }

    @Test
    @DisplayName("POST /admin/users/{id}/change-role invokes service and redirects with success message")
    void testChangeRole_Success() throws Exception {
        mockMvc.perform(post("/admin/users/10/change-role")
                        .with(csrf())
                        .with(user(adminUserDetails))
                        .param("roleId", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("successMessage", "Cập nhật vai trò thành công!"));

        verify(adminUserService).changeRole(eq(1L), eq(10L), eq(2L), any());
    }

    @Test
    @DisplayName("POST /admin/users/{id}/reset-password is removed and redirects to /error/404")
    void testResetPasswordEndpoint_Removed() throws Exception {
        mockMvc.perform(post("/admin/users/10/reset-password")
                        .with(csrf())
                        .with(user(adminUserDetails)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/404"));

        verifyNoInteractions(adminUserService);
    }

    @Test
    @DisplayName("GET /admin/users hides reset-password button, renders verification status badges, and shows contact update for other staff")
    void testList_HidesResetPasswordAndShowsVerificationBadgesAndContactModalButton() throws Exception {
        UserSummary selfAdmin = new UserSummary(
                1L,
                "admin@aureliabook.vn",
                "Administrator",
                "0900000001",
                1L,
                "ROLE_ADMIN",
                true,
                LocalDateTime.now(),
                true,
                false
        );
        UserSummary otherStaff = new UserSummary(
                2L,
                "staff@aureliabook.vn",
                "Staff Member",
                "0900000002",
                3L,
                "ROLE_SALE_STAFF",
                true,
                LocalDateTime.now(),
                false,
                false
        );
        UserSummary customer = new UserSummary(
                3L,
                "cust@example.com",
                "Customer User",
                "0900000003",
                4L,
                "ROLE_CUSTOMER",
                true,
                LocalDateTime.now(),
                true,
                true
        );
        Page<UserSummary> page = new PageImpl<>(List.of(selfAdmin, otherStaff, customer));
        when(adminUserService.list(isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/admin/users").with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/reset-password"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Cấp lại MK"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/contact/email/send-otp"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Cách 2"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/users/2/contact")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Chưa xác minh")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Email khôi phục")));
    }

    @Test
    @DisplayName("POST /admin/users/{id}/contact delegates to AdminUserService.updateStaffContact and requires ADMIN role")
    void testUpdateStaffContact_SuccessAndRbac() throws Exception {
        mockMvc.perform(post("/admin/users/10/contact")
                        .with(csrf())
                        .with(user(staffUserDetails))
                        .param("phone", "0912345678")
                        .param("identityVerified", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));

        mockMvc.perform(post("/admin/users/10/contact")
                        .with(csrf())
                        .with(user(adminUserDetails))
                        .param("email", "updated@aureliabook.vn")
                        .param("phone", "0912345678")
                        .param("identityVerified", "true")
                        .param("reason", "Staff changed phone"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(adminUserService).updateStaffContact(eq(1L), eq(10L), any(), any());
    }

    @Test
    @DisplayName("Admin-session email OTP routes are removed and redirect to /error/404")
    void testAdminEmailOtpEndpoints_Removed() throws Exception {
        mockMvc.perform(post("/admin/users/10/contact/email/send-otp")
                        .with(csrf())
                        .with(user(adminUserDetails))
                        .param("email", "recovered@aureliabook.vn")
                        .param("identityVerified", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/404"));

        mockMvc.perform(post("/admin/users/10/contact/email/resend")
                        .with(csrf())
                        .with(user(adminUserDetails)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/404"));

        mockMvc.perform(post("/admin/users/10/contact/email/confirm")
                        .with(csrf())
                        .with(user(adminUserDetails))
                        .param("code", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/404"));

        verifyNoInteractions(adminUserService);
    }

    @Test
    @DisplayName("POST /admin/users/{id}/contact/revoke-verification delegates to revokeEmailVerification")
    void testRevokeEmailVerification_Success() throws Exception {
        mockMvc.perform(post("/admin/users/10/contact/revoke-verification")
                        .with(csrf())
                        .with(user(adminUserDetails)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(adminUserService).revokeEmailVerification(eq(1L), eq(10L), any());
    }

    @Test
    @DisplayName("POST /admin/users/create shows a warning for post-commit delivery failure")
    void testCreate_EmailDeliveryFailed_ShowsWarningWithoutReopeningCreateForm() throws Exception {
        String message = "Tài khoản nhân viên #10 đã được tạo, nhưng chưa xác nhận gửi email.";
        when(adminUserService.create(any(), any(), any()))
                .thenThrow(new AppException(ErrorCode.EMAIL_DELIVERY_FAILED, message));

        mockMvc.perform(post("/admin/users/create")
                        .with(csrf())
                        .with(user(adminUserDetails))
                        .param("email", "staff@aureliabook.vn")
                        .param("fullName", "Staff Name")
                        .param("phone", "0912345678")
                        .param("roleId", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("warningMessage", message))
                .andExpect(flash().attributeCount(1))
                .andExpect(result -> assertThat(result.getFlashMap().get("successMessage")).isNull());

        verify(adminUserService).create(eq(1L), any(), any());
    }

    @Test
    @DisplayName("GET /admin/users list view includes overflow-x-auto for responsive horizontal scrolling")
    void testListView_ContainsOverflowXAuto() throws Exception {
        when(adminUserService.list(any(), any(), any(), any())).thenReturn(Page.empty());
        mockMvc.perform(get("/admin/users").with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("overflow-x-auto")));
    }

    @Test
    @DisplayName("GET /admin/users renders localized Vietnamese UI labels without redundant English suffixes or rule IDs")
    void testListView_RendersPureVietnameseLabelsWithoutEnglishClutter() throws Exception {
        UserSummary selfAdmin = new UserSummary(
                1L, "admin@aureliabook.vn", "Quản trị viên A", "0900000001", 1L, "ROLE_ADMIN", true, LocalDateTime.now(), false, false
        );
        UserSummary managerUser = new UserSummary(
                2L, "manager@aureliabook.vn", "Quản lý B", "0900000002", 2L, "ROLE_MANAGER", true, LocalDateTime.now(), false, false
        );
        Page<UserSummary> page = new PageImpl<>(List.of(selfAdmin, managerUser));
        when(adminUserService.list(isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/admin/users").with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Quản lý tài khoản nội bộ")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Tài khoản nội bộ")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Bạn không thể tự khóa tài khoản của chính mình")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Quản lý kho &amp; danh mục")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Quản trị tài khoản, phân quyền và giám sát toàn hệ thống")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Quản lý sản phẩm, nhà cung cấp, nhập kho và khuyến mại")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Tiếp nhận, xử lý đơn hàng và lập phiếu nhập nháp")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Phân quyền RBAC"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("(ADMIN)"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("(MANAGER)"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("(SALE_STAFF)"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("(CUSTOMER)"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("(BR-08-01)"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("System administrator"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Store Manager"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Sales Staff"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(">ADMIN<"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(">MANAGER<"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(">SALE_STAFF<"))));
    }

    @Test
    @DisplayName("INT-02 / INT-10: POST /admin/users/{id}/reset-password by non-ADMIN is rejected without invoking service")
    void testResetPassword_NonAdmin_Forbidden() throws Exception {
        mockMvc.perform(post("/admin/users/10/reset-password")
                        .with(csrf())
                        .with(user(staffUserDetails)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));

        verifyNoInteractions(adminUserService);
    }

    @Test
    @DisplayName("GET /admin/users renders wide workspace container and accessible confirm modal without redundant quick filters or browser confirm()")
    void testListView_RendersAccessibleConfirmModalWithoutNativeConfirm() throws Exception {
        UserSummary staffUser = new UserSummary(
                2L, "staff@aureliabook.vn", "Nhân viên B", "0900000002", 3L, "ROLE_SALE_STAFF", true, LocalDateTime.now(), false, false
        );
        when(adminUserService.list(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(staffUser)));

        mockMvc.perform(get("/admin/users").with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("max-w-[1600px]")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"confirmActionModal\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-custom-modal=\"true\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-confirm-form=\"true\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-confirm-select=\"true\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Lọc nhanh"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("return confirm("))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("if(confirm("))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("window.confirm("))));
    }
}
