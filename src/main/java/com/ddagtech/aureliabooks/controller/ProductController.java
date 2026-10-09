package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.service.ProductService;
import com.ddagtech.aureliabooks.util.PageableUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** UC01/02/03 (GET page shell only). Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
@Controller
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/products")
    public String list(
            @ModelAttribute("filter")ProductFilterRequest filterRequest,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "12") Integer size,
            @RequestParam(defaultValue = "newest") String sort,
            Model model) {
        // TODO UC01/02/03: populate Model through service/DTO contracts after implementation.

        Pageable pageable = PageableUtils.create(page,size,sort);

        Page<ProductSummary> productSummaryPage = productService.browse(filterRequest, pageable);
        List<CategorySummary> categorySummaryList = productService.getActiveCategories();
        model.addAttribute("products",productSummaryPage);
        model.addAttribute("categories", categorySummaryList);
        model.addAttribute("currentSort", sort);
        model.addAttribute("currentSize", size);

        return "product/list";
    }
    @GetMapping("/products/search")
    public String search(
            @ModelAttribute("filter") ProductFilterRequest filterRequest,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "12") Integer size,
            @RequestParam(defaultValue = "newest") String sort,
            Model model) {
        Pageable pageable = PageableUtils.create(page, size, sort);

        Page<ProductSummary> products = productService.browse(filterRequest, pageable);
        List<CategorySummary> categories = productService.getActiveCategories();

        model.addAttribute("products", products);
        model.addAttribute("categories", categories);
        model.addAttribute("currentSort", sort);
        model.addAttribute("currentSize", size);
        model.addAttribute("keyword", filterRequest.keyword());

        return "product/search";
    }
    @GetMapping("/products/{id}")
    public String detail(@PathVariable Long id, Model model) {
        try {
            com.ddagtech.aureliabooks.dto.response.ProductDetailResponse product = productService.viewDetail(id);
            model.addAttribute("product", product);
            return "product/detail";
        } catch (java.util.NoSuchElementException ex) {
            return "redirect:/products";
        }
    }
}
