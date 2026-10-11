package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.request.AuthorForm;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.service.MasterDataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for back-office Master Taxonomy management (UC17: Authors, UC18: Publishers, UC19: Brands, UC20: Categories).
 * Enforces role-based authorization for Store Managers and System Administrators.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
public class MasterDataController {

    private final MasterDataService masterDataService;

    // =========================================================================
    // UC17: AUTHORS MANAGEMENT (SCR-M03a)
    // =========================================================================

    /**
     * Displays the Author directory management screen with search and pagination.
     *
     * @param keyword  optional search keyword
     * @param pageable pagination options
     * @param model    Spring MVC model
     * @return template path for author management
     */
    @GetMapping("/manager/authors")
    public String authors(@RequestParam(value = "keyword", required = false) String keyword,
                          @PageableDefault(size = 10) Pageable pageable,
                          Model model) {
        Page<Author> authorsPage = masterDataService.listAuthors(keyword, pageable);
        model.addAttribute("authorsPage", authorsPage);
        model.addAttribute("keyword", keyword != null ? keyword : "");

        Map<Long, Long> bookCounts = new HashMap<>();
        for (Author author : authorsPage.getContent()) {
            bookCounts.put(author.getId(), masterDataService.countBooksByAuthor(author.getId()));
        }
        model.addAttribute("bookCounts", bookCounts);

        if (!model.containsAttribute("authorForm")) {
            model.addAttribute("authorForm", new AuthorForm(null, "", ""));
        }

        return "admin/catalog/authors";
    }

    /**
     * Handles author creation.
     */
    @PostMapping("/manager/authors")
    public String createAuthor(@Valid @ModelAttribute("authorForm") AuthorForm form,
                               BindingResult bindingResult,
                               @RequestParam(value = "keyword", required = false) String keyword,
                               @PageableDefault(size = 10) Pageable pageable,
                               RedirectAttributes redirectAttributes,
                               Model model) {
        if (bindingResult.hasErrors()) {
            Page<Author> authorsPage = masterDataService.listAuthors(keyword, pageable);
            model.addAttribute("authorsPage", authorsPage);
            model.addAttribute("keyword", keyword != null ? keyword : "");
            Map<Long, Long> bookCounts = new HashMap<>();
            for (Author author : authorsPage.getContent()) {
                bookCounts.put(author.getId(), masterDataService.countBooksByAuthor(author.getId()));
            }
            model.addAttribute("bookCounts", bookCounts);
            return "admin/catalog/authors";
        }

        try {
            masterDataService.createAuthor(form);
            redirectAttributes.addFlashAttribute("successMessage", "Thêm tác giả mới thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }

        return "redirect:/manager/authors";
    }

    /**
     * Handles author updating.
     */
    @PostMapping("/manager/authors/{id}/edit")
    public String updateAuthor(@PathVariable("id") Long id,
                               @Valid @ModelAttribute("authorForm") AuthorForm form,
                               BindingResult bindingResult,
                               RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            String firstError = bindingResult.getFieldErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("errorMessage", firstError);
            return "redirect:/manager/authors";
        }

        try {
            masterDataService.updateAuthor(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật thông tin tác giả thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }

        return "redirect:/manager/authors";
    }

    /**
     * Handles author deletion with reference integrity validation.
     */
    @PostMapping("/manager/authors/{id}/delete")
    public String deleteAuthor(@PathVariable("id") Long id,
                               RedirectAttributes redirectAttributes) {
        try {
            masterDataService.deleteAuthor(id);
            redirectAttributes.addFlashAttribute("successMessage", "Xóa tác giả thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/authors";
    }

    // =========================================================================
    // UC18/19/20 SHELLS
    // =========================================================================

    @GetMapping("/manager/publishers")
    public String publishers() {
        return "admin/master_data/list";
    }

    @GetMapping("/manager/brands")
    public String brands() {
        return "admin/master_data/list";
    }

    @GetMapping("/manager/categories")
    public String categories() {
        return "admin/categories/tree";
    }
}
