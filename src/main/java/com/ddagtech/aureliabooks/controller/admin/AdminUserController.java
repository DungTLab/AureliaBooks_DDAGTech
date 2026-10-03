package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.dto.response.UserSummary;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.AdminUserService;
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
import java.util.Set;

/**
 * Controller managing internal back-office staff accounts and RBAC role assignments (UC28).
 * Restricted to administrators via {@code @PreAuthorize("hasRole('ADMIN')")}.
 * Follows the Post-Redirect-Get (PRG) pattern for state-mutating actions.
 */
@Slf4j
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private static final Set<String> INTERNAL_ROLE_NAMES = Set.of(
            "ROLE_ADMIN",
            "ROLE_MANAGER",
            "ROLE_SALE_STAFF"
    );

    private final AdminUserService adminUserService;
    private final RoleRepository roleRepository;

    /**
     * Displays the paginated staff user list filtered optionally by role.
     *
     * @param role optional role name filter
     * @param page zero-based page index
     * @param size page size (defaults to 10)
     * @param model MVC model
     * @return Thymeleaf view path
     */
    @GetMapping
    public String list(
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model
    ) {
        Page<UserSummary> usersPage = adminUserService.list(
                role,
                PageRequest.of(page, size, Sort.by("id").descending())
        );

        List<Role> internalRoles = roleRepository.findAll().stream()
                .filter(r -> INTERNAL_ROLE_NAMES.contains(r.getRoleName()))
                .toList();

        model.addAttribute("usersPage", usersPage);
        model.addAttribute("selectedRole", role);
        model.addAttribute("roles", internalRoles);
        if (!model.containsAttribute("newUserForm")) {
            model.addAttribute("newUserForm", new UserCreateRequest("", "", "", "", null));
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
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            String firstError = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("errorMessage", firstError);
            redirectAttributes.addFlashAttribute("newUserForm", request);
            return "redirect:/admin/users";
        }

        Long adminId = admin != null ? admin.getId() : null;
        adminUserService.create(adminId, request);
        redirectAttributes.addFlashAttribute("successMessage", "Tạo tài khoản nhân viên thành công!");
        return "redirect:/admin/users";
    }

    /**
     * Toggles an internal user active status.
     *
     * @param admin authenticated administrator principal
     * @param id target user identifier
     * @param active desired active state
     * @param redirectAttributes redirect attributes for flash messaging
     * @return redirect URL
     */
    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long id,
            @RequestParam boolean active,
            RedirectAttributes redirectAttributes
    ) {
        Long adminId = admin != null ? admin.getId() : null;
        adminUserService.setActive(adminId, id, active);
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
     * @param redirectAttributes redirect attributes for flash messaging
     * @return redirect URL
     */
    @PostMapping("/{id}/change-role")
    public String changeRole(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long id,
            @RequestParam Long roleId,
            RedirectAttributes redirectAttributes
    ) {
        Long adminId = admin != null ? admin.getId() : null;
        adminUserService.changeRole(adminId, id, roleId);
        redirectAttributes.addFlashAttribute("successMessage", "Cập nhật vai trò thành công!");
        return "redirect:/admin/users";
    }
}

