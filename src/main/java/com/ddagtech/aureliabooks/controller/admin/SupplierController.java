package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.request.SupplierForm;
import com.ddagtech.aureliabooks.dto.response.SupplierSummary;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.service.SupplierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for managing the Supplier Directory (UC30).
 * Restricted to Manager and Admin roles.
 * Implements full CRUD, active/inactive toggling, and receipt protection rules.
 */
@Slf4j
@Controller
@RequestMapping("/manager/suppliers")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    /**
     * Displays the paginated supplier directory list filtered by keyword and active state.
     */
    @GetMapping
    public String list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model
    ) {
        Page<SupplierSummary> suppliersPage = supplierService.list(
                keyword,
                active,
                PageRequest.of(page, size, Sort.by("id").descending())
        );

        model.addAttribute("suppliersPage", suppliersPage);
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedActive", active);

        if (!model.containsAttribute("supplierForm")) {
            model.addAttribute("supplierForm", new SupplierForm("", "", "", "", "", true));
        }

        return "admin/suppliers/list";
    }

    /**
     * Provisions a new supplier in the catalog.
     */
    @PostMapping("/create")
    public String create(
            @Valid @ModelAttribute("supplierForm") SupplierForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            String firstError = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("errorMessage", firstError);
            redirectAttributes.addFlashAttribute("supplierForm", form);
            redirectAttributes.addFlashAttribute("openCreateModal", true);
            return "redirect:/manager/suppliers";
        }

        try {
            supplierService.create(form);
            redirectAttributes.addFlashAttribute("successMessage", "Thêm nhà cung cấp mới thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            redirectAttributes.addFlashAttribute("supplierForm", form);
            redirectAttributes.addFlashAttribute("openCreateModal", true);
        }

        return "redirect:/manager/suppliers";
    }

    /**
     * Updates an existing supplier's information.
     */
    @PostMapping("/{id}/update")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("editSupplierForm") SupplierForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            String firstError = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("errorMessage", firstError);
            return "redirect:/manager/suppliers";
        }

        try {
            supplierService.update(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật thông tin nhà cung cấp thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }

        return "redirect:/manager/suppliers";
    }

    /**
     * Toggles a supplier's active status between active and inactive.
     */
    @PostMapping("/{id}/toggle-status")
    public String toggleStatus(
            @PathVariable Long id,
            @RequestParam boolean active,
            RedirectAttributes redirectAttributes
    ) {
        try {
            supplierService.toggleActive(id, active);
            String message = active
                    ? "Đã kích hoạt lại hợp tác với nhà cung cấp thành công!"
                    : "Đã chuyển nhà cung cấp sang trạng thái Ngừng hợp tác!";
            redirectAttributes.addFlashAttribute("successMessage", message);
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }

        return "redirect:/manager/suppliers";
    }

    /**
     * Hard-deletes a supplier if no goods receipts have been recorded against it.
     */
    @PostMapping("/{id}/delete")
    public String delete(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            supplierService.delete(id);
            redirectAttributes.addFlashAttribute("successMessage", "Xóa nhà cung cấp thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }

        return "redirect:/manager/suppliers";
    }

    /**
     * Provides supplier summary details in JSON format (e.g. for dynamic client-side forms).
     */
    @GetMapping("/{id}")
    @ResponseBody
    public ResponseEntity<SupplierSummary> getDetail(@PathVariable Long id) {
        return ResponseEntity.ok(supplierService.getSummaryById(id));
    }
}
