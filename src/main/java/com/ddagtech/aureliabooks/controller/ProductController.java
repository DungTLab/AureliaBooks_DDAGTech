package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.ProductAutoCompleteResponse;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.service.ProductService;
import com.ddagtech.aureliabooks.util.PageableUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
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
        List<CategorySummary> categories = productService.getActiveCategories();

        String rawKeyword = filterRequest != null ? filterRequest.keyword() : null;
        boolean isBlankKeyWord = rawKeyword == null || rawKeyword.trim().isBlank();



//        Page<ProductSummary> products = productService.browse(filterRequest, pageable);
        Page<ProductSummary> products;
        if(isBlankKeyWord){
            products = Page.empty(pageable);
            model.addAttribute("keywordMessage","Vui lòng nhập từ khóa để tìm kiếm sản phẩm");
        } else {
            ProductFilterRequest sanitizedFilter = new ProductFilterRequest(
                    rawKeyword.trim(),
                    filterRequest.categoryId(),
                    filterRequest.minPrice(),
                    filterRequest.maxPrice(),
                    filterRequest.authorId(),
                    filterRequest.publisherId(),
                    filterRequest.brandId(),
                    filterRequest.coverType(),
                    filterRequest.productType(),
                    filterRequest.isTextbook()
            );
            products = productService.browse(sanitizedFilter,pageable);
        }
        model.addAttribute("products", products);
        model.addAttribute("categories", categories);
        model.addAttribute("currentSort", sort);
        model.addAttribute("currentSize", size);
        model.addAttribute("keyword", isBlankKeyWord ? "" : rawKeyword.trim());

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

    @GetMapping("/products/autocomplete")
    @ResponseBody
    public List<ProductAutoCompleteResponse> autoCompleteResponses(@RequestParam(required = false) String keyword){
        if(keyword==null||keyword.trim().length()<2){
            return List.of();
        }
        return productService.autocomplete(keyword.trim());
    }
}
