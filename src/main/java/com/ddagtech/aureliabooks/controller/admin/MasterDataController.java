package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.request.AuthorForm;
import com.ddagtech.aureliabooks.dto.request.BrandForm;
import com.ddagtech.aureliabooks.dto.request.CategoryForm;
import com.ddagtech.aureliabooks.dto.request.PublisherForm;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Brand;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Publisher;
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
import java.util.List;
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

    @PostMapping("/manager/authors/{id}/edit")
    public String updateAuthor(@PathVariable("id") Long id,
                               @Valid @ModelAttribute("authorForm") AuthorForm form,
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
            model.addAttribute("editAuthorId", id);
            return "admin/catalog/authors";

        }

        try {
            masterDataService.updateAuthor(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật thông tin tác giả thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/authors";
    }

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
    // UC18: PUBLISHERS MANAGEMENT (SCR-M03b)
    // =========================================================================

    @GetMapping("/manager/publishers")
    public String publishers(@RequestParam(value = "keyword", required = false) String keyword,
                             @PageableDefault(size = 10) Pageable pageable,
                             Model model) {
        Page<Publisher> publishersPage = masterDataService.listPublishers(keyword, pageable);
        model.addAttribute("publishersPage", publishersPage);
        model.addAttribute("keyword", keyword != null ? keyword : "");

        Map<Long, Long> bookCounts = new HashMap<>();
        for (Publisher publisher : publishersPage.getContent()) {
            bookCounts.put(publisher.getId(), masterDataService.countBooksByPublisher(publisher.getId()));
        }
        model.addAttribute("bookCounts", bookCounts);

        if (!model.containsAttribute("publisherForm")) {
            model.addAttribute("publisherForm", new PublisherForm("", "", ""));
        }
        return "admin/catalog/publishers";
    }

    @PostMapping("/manager/publishers")
    public String createPublisher(@Valid @ModelAttribute("publisherForm") PublisherForm form,
                                  BindingResult bindingResult,
                                  RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", bindingResult.getFieldErrors().get(0).getDefaultMessage());
            return "redirect:/manager/publishers";
        }
        try {
            masterDataService.createPublisher(form);
            redirectAttributes.addFlashAttribute("successMessage", "Thêm nhà xuất bản mới thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/publishers";
    }

    @PostMapping("/manager/publishers/{id}/edit")
    public String updatePublisher(@PathVariable("id") Long id,
                                  @Valid @ModelAttribute("publisherForm") PublisherForm form,
                                  BindingResult bindingResult,
                                  RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", bindingResult.getFieldErrors().get(0).getDefaultMessage());
            return "redirect:/manager/publishers";
        }
        try {
            masterDataService.updatePublisher(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật nhà xuất bản thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/publishers";
    }

    @PostMapping("/manager/publishers/{id}/delete")
    public String deletePublisher(@PathVariable("id") Long id,
                                  RedirectAttributes redirectAttributes) {
        try {
            masterDataService.deletePublisher(id);
            redirectAttributes.addFlashAttribute("successMessage", "Xóa nhà xuất bản thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/publishers";
    }

    // =========================================================================
    // UC19: BRANDS MANAGEMENT (SCR-M03c)
    // =========================================================================

    @GetMapping("/manager/brands")
    public String brands(@RequestParam(value = "keyword", required = false) String keyword,
                         @PageableDefault(size = 10) Pageable pageable,
                         Model model) {
        Page<Brand> brandsPage = masterDataService.listBrands(keyword, pageable);
        model.addAttribute("brandsPage", brandsPage);
        model.addAttribute("keyword", keyword != null ? keyword : "");

        Map<Long, Long> stationeryCounts = new HashMap<>();
        for (Brand brand : brandsPage.getContent()) {
            stationeryCounts.put(brand.getId(), masterDataService.countStationeriesByBrand(brand.getId()));
        }
        model.addAttribute("stationeryCounts", stationeryCounts);

        if (!model.containsAttribute("brandForm")) {
            model.addAttribute("brandForm", new BrandForm("", ""));
        }
        return "admin/catalog/brands";
    }

    @PostMapping("/manager/brands")
    public String createBrand(@Valid @ModelAttribute("brandForm") BrandForm form,
                              BindingResult bindingResult,
                              RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", bindingResult.getFieldErrors().get(0).getDefaultMessage());
            return "redirect:/manager/brands";
        }
        try {
            masterDataService.createBrand(form);
            redirectAttributes.addFlashAttribute("successMessage", "Thêm thương hiệu mới thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/brands";
    }

    @PostMapping("/manager/brands/{id}/edit")
    public String updateBrand(@PathVariable("id") Long id,
                              @Valid @ModelAttribute("brandForm") BrandForm form,
                              BindingResult bindingResult,
                              RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", bindingResult.getFieldErrors().get(0).getDefaultMessage());
            return "redirect:/manager/brands";
        }
        try {
            masterDataService.updateBrand(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật thương hiệu thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/brands";
    }

    @PostMapping("/manager/brands/{id}/delete")
    public String deleteBrand(@PathVariable("id") Long id,
                              RedirectAttributes redirectAttributes) {
        try {
            masterDataService.deleteBrand(id);
            redirectAttributes.addFlashAttribute("successMessage", "Xóa thương hiệu thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/brands";
    }

    // =========================================================================
    // UC20: CATEGORIES MANAGEMENT (SCR-M03d)
    // =========================================================================

    @GetMapping("/manager/categories")
    public String categories(Model model) {
        List<Category> allCategories = masterDataService.getAllCategoriesWithParent();
        model.addAttribute("categories", allCategories);

        Map<Long, Long> productCounts = new HashMap<>();
        for (Category cat : allCategories) {
            productCounts.put(cat.getId(), masterDataService.countProductsByCategory(cat.getId()));
        }
        model.addAttribute("productCounts", productCounts);

        if (!model.containsAttribute("categoryForm")) {
            model.addAttribute("categoryForm", new CategoryForm("", null, "", true));
        }
        return "admin/catalog/categories";
    }

    @PostMapping("/manager/categories")
    public String createCategory(@Valid @ModelAttribute("categoryForm") CategoryForm form,
                                 BindingResult bindingResult,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", bindingResult.getFieldErrors().get(0).getDefaultMessage());
            return "redirect:/manager/categories";
        }
        try {
            masterDataService.createCategory(form);
            redirectAttributes.addFlashAttribute("successMessage", "Thêm danh mục mới thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/categories";
    }

    @PostMapping("/manager/categories/{id}/edit")
    public String updateCategory(@PathVariable("id") Long id,
                                 @Valid @ModelAttribute("categoryForm") CategoryForm form,
                                 BindingResult bindingResult,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", bindingResult.getFieldErrors().get(0).getDefaultMessage());
            return "redirect:/manager/categories";
        }
        try {
            masterDataService.updateCategory(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật danh mục thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/categories";
    }

    @PostMapping("/manager/categories/{id}/delete")
    public String deleteCategory(@PathVariable("id") Long id,
                                 RedirectAttributes redirectAttributes) {
        try {
            masterDataService.deleteCategory(id);
            redirectAttributes.addFlashAttribute("successMessage", "Xóa danh mục thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/categories";
    }

    @PostMapping("/manager/categories/{id}/toggle-status")
    public String toggleCategoryStatus(@PathVariable("id") Long id,
                                       RedirectAttributes redirectAttributes) {
        try {
            masterDataService.toggleCategoryStatus(id);
            redirectAttributes.addFlashAttribute("successMessage", "Đổi trạng thái hiển thị danh mục thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/categories";
    }
}
