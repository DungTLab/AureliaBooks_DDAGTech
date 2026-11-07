package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@WebMvcTest(controllers = HomeController.class)
@Import(SecurityConfig.class)
class HomeControllerTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ddagtech.aureliabooks.service.ProductService homepageProducts;

    @org.junit.jupiter.api.BeforeEach
    void setupHomepageCatalogue() {
        org.mockito.Mockito.when(homepageProducts.browse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        org.mockito.Mockito.when(homepageProducts.browse(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
    }


    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET / should return 200 OK and render index template with title")
    void testHomePageReturns200AndRendersIndex() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeExists("title"));
    }

    @Test
    @DisplayName("GET /home should return 200 OK and render index template")
    void testHomePathReturns200AndRendersIndex() throws Exception {
        mockMvc.perform(get("/home"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeExists("title"));
    }
    @Test
    @DisplayName("Homepage includes mobile navigation behavior inside the rendered fragment")
    void rendersMobileNavigationScript() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"mobileMenuBtn\"")))
                .andExpect(content().string(containsString("const mobileMenuBtn")))
                .andExpect(content().string(containsString("mobileMenuBtn.addEventListener")));
    }

    @Test
    @DisplayName("Authenticated homepage includes dropdown behavior and toast rendering")
    void rendersDropdownAndToastScript() throws Exception {
        mockMvc.perform(get("/").with(user("layout-review").roles("CUSTOMER"))
                        .flashAttr("successMessage", "LAYOUT_TOAST_TEST"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"userMenuBtn\"")))
                .andExpect(content().string(containsString("userMenuBtn.addEventListener")))
                .andExpect(content().string(containsString("LAYOUT_TOAST_TEST")))
                .andExpect(content().string(containsString("function closeToast")));
    }
    @Test
    void rendersLivePricesProductLinksAndTypeSections() throws Exception {
        var book = new com.ddagtech.aureliabooks.dto.response.ProductSummary(42L, "Cẩm nang đọc 2026",
                new java.math.BigDecimal("123000"), 5, null);
        var stationery = new com.ddagtech.aureliabooks.dto.response.ProductSummary(77L, "Bút mực dữ liệu thật",
                new java.math.BigDecimal("15000"), 8, "/images/test-pen.png",
                com.ddagtech.aureliabooks.entity.Product.ProductType.STATIONERY, null, null);
        org.mockito.Mockito.when(homepageProducts.browse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    var filter = call.getArgument(0, com.ddagtech.aureliabooks.dto.request.ProductFilterRequest.class);
                    return new org.springframework.data.domain.PageImpl<>(java.util.List.of(filter == null ? book : stationery));
                });
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("catalogueCount", 1L))
                .andExpect(content().string(containsString("Cẩm nang đọc 2026")))
                .andExpect(content().string(containsString("123.000đ")))
                .andExpect(content().string(containsString("/products/42")))
                .andExpect(content().string(containsString("/products/77")))
                .andExpect(content().string(containsString("Bút mực dữ liệu thật")))
                .andExpect(content().string(containsString("/images/product-placeholder.svg")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Giảm 20%"))));
    }

    @Test
    void emptyCatalogueShowsHonestEmptyStateWithoutMockProducts() throws Exception {
        mockMvc.perform(get("/home")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa có sản phẩm đang bán")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("data-home-product-id="))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Clean Code - Nghệ Thuật"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("GIẢM ĐẾN 30%"))));
    }
}
