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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

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

        return "product/list";
    }
    @GetMapping("/products/search")
    public String search() {
        // TODO UC01/02/03: populate Model through service/DTO contracts after implementation.
        return "product/search";
    }
    @GetMapping("/products/{id}")
    public String detail() {
        // TODO UC01/02/03: populate Model through service/DTO contracts after implementation.
        return "product/detail";
    }
}
