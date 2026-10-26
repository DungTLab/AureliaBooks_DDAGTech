package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.service.ProductService;
import com.ddagtech.aureliabooks.util.PageableUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller serving the Storefront Homepage and landing experience.
 */
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final ProductService products;

    @GetMapping({"/", "/home"})
    public String index(Model model) {
        var latest = products.browse(null, PageableUtils.create(1, 8, "newest"));
        var featured = products.browse(null, PageableUtils.create(1, 8, "best_sellers"));
        var stationeryFilter = new ProductFilterRequest(null, null, null, null, null, null, null,
                null, Product.ProductType.STATIONERY, null);
        var stationery = products.browse(stationeryFilter, PageableUtils.create(1, 4, "newest"));
        model.addAttribute("newArrivals", latest.getContent());
        model.addAttribute("featuredProducts", featured.getContent());
        model.addAttribute("stationeryProducts", stationery.getContent());
        model.addAttribute("catalogueCount", latest.getTotalElements());
        model.addAttribute("heroProduct", latest.isEmpty() ? null : latest.getContent().getFirst());
        model.addAttribute("title", "Trang Chủ - Thế Giới Sách & VPP");
        return "index";
    }
}
