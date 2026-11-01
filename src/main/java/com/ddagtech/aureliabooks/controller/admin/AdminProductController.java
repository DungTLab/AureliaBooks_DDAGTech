package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.request.BookForm;
import com.ddagtech.aureliabooks.dto.request.StationeryForm;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.service.AdminProductService;
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

import java.util.Set;

/**
 * Controller for back-office Product Management (UC15: Books, UC16: Stationery).
 * Enforces business rules and role authorization for Store Managers and Administrators.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class AdminProductController {

    private final AdminProductService adminProductService;
    private final MasterDataService masterDataService;

    @org.springframework.beans.factory.annotation.Value("${app.r2.enabled:false}")
    private boolean productImageUploadEnabled;

    @ModelAttribute("productImageUploadEnabled")
    public boolean productImageUploadEnabled() { return productImageUploadEnabled; }

    // =========================================================================
    // PRODUCT DIRECTORY (BOOKS & STATIONERY)
    // =========================================================================

    /**
     * Lists catalog products with optional filtering by type and keyword.
     */
    @GetMapping("/manager/products")
    public String list(@RequestParam(value = "type", required = false) Product.ProductType type,
                       @RequestParam(value = "keyword", required = false) String keyword,
                       @PageableDefault(size = 10) Pageable pageable,
                       Model model) {
        Page<Product> productsPage = adminProductService.listProducts(type, keyword, pageable);
        model.addAttribute("productsPage", productsPage);
        model.addAttribute("selectedType", type);
        model.addAttribute("keyword", keyword != null ? keyword : "");
        return "admin/products/list";
    }

    @GetMapping("/manager/books")
    public String listBooks(@RequestParam(value = "keyword", required = false) String keyword,
                            @PageableDefault(size = 10) Pageable pageable,
                            Model model) {
        return list(Product.ProductType.BOOK, keyword, pageable, model);
    }

    @GetMapping("/manager/products/new")
    public String newProduct() {
        return "redirect:/manager/books/new";
    }

    @GetMapping("/manager/stationeries")
    public String listStationeries(@RequestParam(value = "keyword", required = false) String keyword,
                                   @PageableDefault(size = 10) Pageable pageable,
                                   Model model) {
        return list(Product.ProductType.STATIONERY, keyword, pageable, model);
    }

    /**
     * Toggles active/inactive status of a product (soft hide from storefront).
     */
    @PostMapping("/manager/products/{id}/toggle-status")
    public String toggleStatus(@PathVariable("id") Long id,
                               RedirectAttributes redirectAttributes) {
        try {
            adminProductService.toggleActive(id);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật trạng thái hiển thị của sản phẩm thành công!");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/products";
    }

    // =========================================================================
    // UC15: BOOKS MANAGEMENT
    // =========================================================================

    @GetMapping("/manager/books/new")
    public String newBookForm(Model model) {
        if (!model.containsAttribute("bookForm")) {
            model.addAttribute("bookForm", new BookForm(
                    null, "", "", null, null, null, 200, "", "", "", "", null,
                    Set.of(), "", null, false, 2026, "1", null, "Tiếng Việt", Book.CoverType.PAPERBACK
            ));
        }
        populateBookFormData(model);
        model.addAttribute("isEdit", false);
        return "admin/products/book-form";
    }

    @PostMapping("/manager/books")
    public String createBook(@Valid @ModelAttribute("bookForm") BookForm form,
                             BindingResult bindingResult,
                             RedirectAttributes redirectAttributes,
                             Model model) {
        if (bindingResult.hasErrors()) {
            populateBookFormData(model);
            model.addAttribute("isEdit", false);
            return "admin/products/book-form";
        }

        try {
            adminProductService.createBook(form);
            redirectAttributes.addFlashAttribute("successMessage", "Thêm tựa sách mới thành công!");
            return "redirect:/manager/products?type=BOOK";
        } catch (AppException e) {
            model.addAttribute("errorMessage", e.getMessage());
            populateBookFormData(model);
            model.addAttribute("isEdit", false);
            return "admin/products/book-form";
        }
    }

    @GetMapping("/manager/books/{id}/edit")
    public String editBookForm(@PathVariable("id") Long id, Model model) {
        BookForm bookForm = adminProductService.getBookForEdit(id);
        model.addAttribute("bookForm", bookForm);
        populateBookFormData(model);
        model.addAttribute("isEdit", true);
        return "admin/products/book-form";
    }

    @PostMapping("/manager/books/{id}/edit")
    public String updateBook(@PathVariable("id") Long id,
                             @Valid @ModelAttribute("bookForm") BookForm form,
                             BindingResult bindingResult,
                             RedirectAttributes redirectAttributes,
                             Model model) {
        if (bindingResult.hasErrors()) {
            populateBookFormData(model);
            model.addAttribute("isEdit", true);
            return "admin/products/book-form";
        }

        try {
            adminProductService.updateBook(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật thông tin sách thành công!");
            return "redirect:/manager/products?type=BOOK";
        } catch (AppException e) {
            model.addAttribute("errorMessage", e.getMessage());
            populateBookFormData(model);
            model.addAttribute("isEdit", true);
            return "admin/products/book-form";
        }
    }

    private void populateBookFormData(Model model) {
        model.addAttribute("categories", masterDataService.getAllActiveCategories());
        model.addAttribute("publishers", masterDataService.getAllPublishers());
        model.addAttribute("authors", masterDataService.getAllAuthors());
    }

    // =========================================================================
    // UC16: STATIONERY MANAGEMENT
    // =========================================================================

    @GetMapping("/manager/stationeries/new")
    public String newStationeryForm(Model model) {
        if (!model.containsAttribute("stationeryForm")) {
            model.addAttribute("stationeryForm", new StationeryForm(
                    null, "", "", null, null, null, 200, "", "", "", null, "", "", 0
            ));
        }
        populateStationeryFormData(model);
        model.addAttribute("isEdit", false);
        return "admin/products/stationery-form";
    }

    @PostMapping("/manager/stationeries")
    public String createStationery(@Valid @ModelAttribute("stationeryForm") StationeryForm form,
                                  BindingResult bindingResult,
                                  RedirectAttributes redirectAttributes,
                                  Model model) {
        if (bindingResult.hasErrors()) {
            populateStationeryFormData(model);
            model.addAttribute("isEdit", false);
            return "admin/products/stationery-form";
        }

        try {
            adminProductService.createStationery(form);
            redirectAttributes.addFlashAttribute("successMessage", "Thêm văn phòng phẩm mới thành công!");
            return "redirect:/manager/products?type=STATIONERY";
        } catch (AppException e) {
            model.addAttribute("errorMessage", e.getMessage());
            populateStationeryFormData(model);
            model.addAttribute("isEdit", false);
            return "admin/products/stationery-form";
        }
    }

    @GetMapping("/manager/stationeries/{id}/edit")
    public String editStationeryForm(@PathVariable("id") Long id, Model model) {
        StationeryForm stationeryForm = adminProductService.getStationeryForEdit(id);
        model.addAttribute("stationeryForm", stationeryForm);
        populateStationeryFormData(model);
        model.addAttribute("isEdit", true);
        return "admin/products/stationery-form";
    }

    @PostMapping("/manager/stationeries/{id}/edit")
    public String updateStationery(@PathVariable("id") Long id,
                                  @Valid @ModelAttribute("stationeryForm") StationeryForm form,
                                  BindingResult bindingResult,
                                  RedirectAttributes redirectAttributes,
                                  Model model) {
        if (bindingResult.hasErrors()) {
            populateStationeryFormData(model);
            model.addAttribute("isEdit", true);
            return "admin/products/stationery-form";
        }

        try {
            adminProductService.updateStationery(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật văn phòng phẩm thành công!");
            return "redirect:/manager/products?type=STATIONERY";
        } catch (AppException e) {
            model.addAttribute("errorMessage", e.getMessage());
            populateStationeryFormData(model);
            model.addAttribute("isEdit", true);
            return "admin/products/stationery-form";
        }
    }

    private void populateStationeryFormData(Model model) {
        model.addAttribute("categories", masterDataService.getAllActiveCategories());
        model.addAttribute("brands", masterDataService.getAllBrands());
    }
}
