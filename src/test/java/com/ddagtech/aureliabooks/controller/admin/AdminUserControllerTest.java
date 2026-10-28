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
                        .param("password", "SecureP@ss123")
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
                        .param("password", "weak")
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
                        .param("password", "SecureP@ss123")
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
    @DisplayName("POST /admin/users/{id}/reset-password invokes service and redirects with success message")
    void testResetPassword_Success() throws Exception {
        mockMvc.perform(post("/admin/users/10/reset-password")
                        .with(csrf())
                        .with(user(adminUserDetails)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("successMessage", org.hamcrest.Matchers.containsString("Cấp lại mật khẩu tạm thời thành công")));

        verify(adminUserService).resetStaffPassword(eq(1L), eq(10L), any());
    }

    @Test
    @DisplayName("POST /admin/users/{id}/reset-password when target is Customer redirects with error message")
    void testResetPassword_CustomerRejected_Error() throws Exception {
        doThrow(new AppException(ErrorCode.CANNOT_RESET_CUSTOMER_PASSWORD))
                .when(adminUserService).resetStaffPassword(eq(1L), eq(50L), any());

        mockMvc.perform(post("/admin/users/50/reset-password")
                        .with(csrf())
                        .with(user(adminUserDetails)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attribute("errorMessage", org.hamcrest.Matchers.containsString("không được phép")));

        verify(adminUserService).resetStaffPassword(eq(1L), eq(50L), any());
    }
}
