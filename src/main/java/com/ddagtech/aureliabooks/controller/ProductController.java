package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.ProductAutoCompleteResponse;
import com.ddagtech.aureliabooks.dto.response.ProductDetailResponse;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.service.ProductService;
import com.ddagtech.aureliabooks.service.RecommendationService;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import com.ddagtech.aureliabooks.util.PageableUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/** Storefront catalogue, search, product details and recommendation entry point. */
@Controller
public class ProductController {
    private final ProductService productService;
    private final ObjectProvider<RecommendationService> recommendations;
    private final ObjectProvider<CustomerIdentity> identity;

    public ProductController(ProductService productService, ObjectProvider<RecommendationService> recommendations,
            ObjectProvider<CustomerIdentity> identity) {
        this.productService = productService;
        this.recommendations = recommendations;
        this.identity = identity;
    }

    @GetMapping("/products")
    public String list(
            @ModelAttribute("filter")ProductFilterRequest filterRequest,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "12") Integer size,
            @RequestParam(defaultValue = "newest") String sort,
            Model model) {

        Pageable pageable = PageableUtils.create(page,size,sort);

        Page<ProductSummary> productSummaryPage = productService.browse(filterRequest, pageable);
        List<CategorySummary> categorySummaryList = productService.getActiveCategories();
        model.addAttribute("products", productSummaryPage);
        model.addAttribute("categories", categorySummaryList);
        model.addAttribute("authors", productService.getAuthors());
        model.addAttribute("publishers", productService.getPublishers());
        model.addAttribute("brands", productService.getBrands());
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
        model.addAttribute("authors", productService.getAuthors());
        model.addAttribute("publishers", productService.getPublishers());
        model.addAttribute("brands", productService.getBrands());
        model.addAttribute("currentSort", sort);
        model.addAttribute("currentSize", size);
        model.addAttribute("keyword", isBlankKeyWord ? "" : rawKeyword.trim());

        return "product/search";
    }
    @GetMapping("/products/{id}")
    public String detail(@PathVariable Long id, Model model, Authentication authentication) {
        try {
            ProductDetailResponse product = productService.viewDetail(id);
            model.addAttribute("product", product);
            RecommendationService service = recommendations.getIfAvailable();
            model.addAttribute("recommendations", service == null ? List.of() : service.similar(id));
            List<ProductSummary> personal = List.of();
            if (service != null && authentication != null && authentication.getAuthorities().stream()
                    .anyMatch(role -> "ROLE_CUSTOMER".equals(role.getAuthority())) && identity.getIfAvailable() != null) {
                personal = service.personalized(identity.getObject().resolve(authentication), id);
            }
            model.addAttribute("personalizedRecommendations", personal);
            return "product/detail";
        } catch (NoSuchElementException ex) {
            return "redirect:/products";
        }
    }

    @GetMapping("/products/autocomplete")
    @ResponseBody
    public List<ProductAutoCompleteResponse> autoCompleteResponses(@RequestParam(required = false) String keyword){
        if(keyword==null||keyword.trim().length()<2){
            return List.of();
        }
        return productService.autoComplete(keyword.trim());
    }
}
