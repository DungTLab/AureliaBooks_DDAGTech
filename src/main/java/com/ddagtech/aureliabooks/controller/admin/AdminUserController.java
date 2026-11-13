package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.request.AdminStaffEmailOtpRequest;
import com.ddagtech.aureliabooks.dto.request.StaffContactUpdateRequest;
import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.dto.response.AdminStaffEmailChallengeView;
import com.ddagtech.aureliabooks.dto.response.UserSummary;
import com.ddagtech.aureliabooks.dto.response.RoleOption;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.AdminUserService;
import com.ddagtech.aureliabooks.service.StaffContactService;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Controller managing internal back-office staff accounts and RBAC role assignments (UC28).
 * Restricted to administrators via {@code @PreAuthorize("hasRole('ADMIN')")}.
 * Follows the Post-Redirect-Get (PRG) pattern for state-mutating actions.
 */
@Slf4j
@Controller
@RequestMapping({"/admin/users", "/admin"})
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final StaffContactService staffContactService;

    /**
     * Displays the paginated staff user list filtered optionally by role, active status, and search keyword.
     *
     * @param role optional role name filter
     * @param active optional account active state filter
     * @param keyword optional search term matching name, email, or phone
     * @param page zero-based page index
     * @param size page size (defaults to 20 per wireframe design)
     * @param admin authenticated administrator principal
     * @param httpRequest incoming servlet request
     * @param model MVC model
     * @return Thymeleaf view path
     */
    @GetMapping
    public String list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails admin,
            HttpServletRequest httpRequest,
            Model model
    ) {
        Page<UserSummary> usersPage = adminUserService.list(
                role,
                active,
                keyword,
                PageRequest.of(page, size, Sort.by("id").descending())
        );

        List<RoleOption> internalRoles = adminUserService.listAssignableRoles();

        model.addAttribute("usersPage", usersPage);
        model.addAttribute("selectedRole", role);
        model.addAttribute("selectedActive", active);
        model.addAttribute("keyword", keyword);
        model.addAttribute("roles", internalRoles);
        if (!model.containsAttribute("newUserForm")) {
            model.addAttribute("newUserForm", new UserCreateRequest("", "", "", null));
        }

        Object otpTargetObj = model.asMap().get("openContactOtpUserId");
        HttpSession session = httpRequest.getSession(false);
        if (otpTargetObj instanceof Long otpTargetId && admin != null && session != null) {
            AdminStaffEmailChallengeView challenge = staffContactService
                    .findAdminEmailChallenge(admin.getId(), session.getId(), otpTargetId)
                    .orElse(null);
            model.addAttribute("activeEmailChallenge", challenge);
        }

        return "admin/users/list";
    }

    /**
     * Provisions a new internal back-office staff account.
     *
     * @param admin authenticated administrator principal
     * @param request creation request DTO
     * @param bindingResult validation results
     * @param redirectAttributes redirect attributes for flash messaging
     * @return redirect URL
     */
    @PostMapping("/create")
    public String create(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @ModelAttribute("newUserForm") UserCreateRequest request,
            BindingResult bindingResult,
            HttpServletRequest httpRequest,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            String firstError = bindingResult.getAllErrors().getFirst().getDefaultMessage();
            redirectAttributes.addFlashAttribute("errorMessage", firstError);
            redirectAttributes.addFlashAttribute("newUserForm", request);
            redirectAttributes.addFlashAttribute("openCreateModal", true);
            return "redirect:/admin/users";
        }

        try {
            Long adminId = admin != null ? admin.getId() : null;
            String clientIp = HttpRequestUtil.getClientIp(httpRequest);
            adminUserService.create(adminId, request, clientIp);
            redirectAttributes.addFlashAttribute("successMessage", "Tạo tài khoản nhân viên thành công!");
            return "redirect:/admin/users";
        } catch (AppException ex) {
            log.warn("Failed to provision internal staff account: {}", ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            redirectAttributes.addFlashAttribute("newUserForm", request);
            redirectAttributes.addFlashAttribute("openCreateModal", true);
            return "redirect:/admin/users";
        }
    }

    /**
     * Toggles an internal user active status.
     *
     * @param admin authenticated administrator principal
     * @param id target user identifier
     * @param active desired active state
     * @param httpRequest incoming HTTP servlet request
     * @param redirectAttributes redirect attributes for flash messaging
     * @return redirect URL
     */
    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long id,
            @RequestParam boolean active,
            HttpServletRequest httpRequest,
            RedirectAttributes redirectAttributes
    ) {
        Long adminId = admin != null ? admin.getId() : null;
        String clientIp = HttpRequestUtil.getClientIp(httpRequest);
        adminUserService.setActive(adminId, id, active, clientIp);
        String message = active ? "Mở khóa tài khoản thành công!" : "Khóa tài khoản thành công!";
        redirectAttributes.addFlashAttribute("successMessage", message);
        return "redirect:/admin/users";
    }

    /**
     * Modifies the internal RBAC role of a user.
     *
     * @param admin authenticated administrator principal
     * @param id target user identifier
     * @param roleId target role identifier
     * @param httpRequest incoming HTTP servlet request
     * @param redirectAttributes redirect attributes for flash messaging
     * @return redirect URL
     */
    @PostMapping("/{id}/change-role")
    public String changeRole(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long id,
            @RequestParam Long roleId,
            HttpServletRequest httpRequest,
            RedirectAttributes redirectAttributes
    ) {
        Long adminId = admin != null ? admin.getId() : null;
        String clientIp = HttpRequestUtil.getClientIp(httpRequest);
        adminUserService.changeRole(adminId, id, roleId, clientIp);
        redirectAttributes.addFlashAttribute("successMessage", "Cập nhật vai trò thành công!");
        return "redirect:/admin/users";
    }

    /**
     * Updates phone and/or unverified email for an internal staff member during lost-channel support (UC28 GIAC-02).
     * Never alters account passwords or automatically marks contacts verified.
     */
    @PostMapping("/{id}/contact")
    public String updateStaffContact(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long id,
            @Valid @ModelAttribute StaffContactUpdateRequest request,
            BindingResult bindingResult,
            HttpServletRequest httpRequest,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", bindingResult.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/admin/users";
        }
        Long adminId = admin != null ? admin.getId() : null;
        String clientIp = HttpRequestUtil.getClientIp(httpRequest);
        try {
            adminUserService.updateStaffContact(adminId, id, request, clientIp);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã cập nhật thông tin liên hệ nhân sự (trạng thái chưa xác minh). Nhân sự có thể tự xác minh tại mục Email khôi phục."
            );
        } catch (AppException ex) {
            log.warn("Failed to update staff contact: {}", ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/users";
    }

    /**
     * Initiates live OTP verification to a staff member's new work email during lost-channel support (UC28 GIAC-02).
     */
    @PostMapping("/{id}/contact/email/send-otp")
    public String sendAdminEmailOtp(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long id,
            @Valid @ModelAttribute AdminStaffEmailOtpRequest request,
            BindingResult bindingResult,
            HttpServletRequest httpRequest,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", bindingResult.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/admin/users";
        }
        Long adminId = admin != null ? admin.getId() : null;
        HttpSession session = httpRequest.getSession(true);
        String clientIp = HttpRequestUtil.getClientIp(httpRequest);
        try {
            AdminStaffEmailChallengeView challenge = staffContactService.startAdminEmailOtp(
                    adminId,
                    session.getId(),
                    id,
                    request,
                    clientIp
            );
            redirectAttributes.addFlashAttribute("openContactOtpUserId", id);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã gửi mã OTP 6 số tới " + challenge.maskedEmail() + ". Yêu cầu nhân sự đọc mã từ hộp thư mới để xác nhận."
            );
        } catch (AppException ex) {
            log.warn("Failed to send admin lost-channel email OTP: {}", ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/users";
    }

    /**
     * Resends the live lost-channel email verification OTP after the 60-second cooldown (UC28 GIAC-02).
     */
    @PostMapping("/{id}/contact/email/resend")
    public String resendAdminEmailOtp(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long id,
            HttpServletRequest httpRequest,
            RedirectAttributes redirectAttributes
    ) {
        Long adminId = admin != null ? admin.getId() : null;
        HttpSession session = httpRequest.getSession(true);
        String clientIp = HttpRequestUtil.getClientIp(httpRequest);
        try {
            AdminStaffEmailChallengeView challenge = staffContactService.resendAdminEmailOtp(
                    adminId,
                    session.getId(),
                    id,
                    clientIp
            );
            redirectAttributes.addFlashAttribute("openContactOtpUserId", id);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã gửi lại mã OTP tới " + challenge.maskedEmail() + "."
            );
        } catch (AppException ex) {
            redirectAttributes.addFlashAttribute("openContactOtpUserId", id);
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/users";
    }

    /**
     * Confirms the 6-digit OTP provided by the staff member during lost-channel support, marking the new
     * recovery email as verified without altering password hashes (UC28 GIAC-02).
     */
    @PostMapping("/{id}/contact/email/confirm")
    public String confirmAdminEmailOtp(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long id,
            @RequestParam(name = "code", defaultValue = "") String code,
            HttpServletRequest httpRequest,
            RedirectAttributes redirectAttributes
    ) {
        Long adminId = admin != null ? admin.getId() : null;
        HttpSession session = httpRequest.getSession(true);
        String clientIp = HttpRequestUtil.getClientIp(httpRequest);
        try {
            staffContactService.confirmAdminEmailOtp(adminId, session.getId(), id, code, clientIp);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Đã xác minh email khôi phục mới qua OTP. Nhân sự có thể tự đặt lại mật khẩu tại /internal/forgot-password."
            );
        } catch (AppException ex) {
            redirectAttributes.addFlashAttribute("openContactOtpUserId", id);
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/users";
    }

    /**
     * Revokes email verification status on another internal staff account (UC28 GIAC-02).
     */
    @PostMapping("/{id}/contact/revoke-verification")
    public String revokeEmailVerification(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long id,
            HttpServletRequest httpRequest,
            RedirectAttributes redirectAttributes
    ) {
        Long adminId = admin != null ? admin.getId() : null;
        String clientIp = HttpRequestUtil.getClientIp(httpRequest);
        try {
            adminUserService.revokeEmailVerification(adminId, id, clientIp);
            redirectAttributes.addFlashAttribute("successMessage", "Đã gỡ trạng thái xác minh email của nhân sự.");
        } catch (AppException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/users";
    }
}

