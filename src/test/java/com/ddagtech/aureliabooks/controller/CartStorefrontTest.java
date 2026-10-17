package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.request.CartQuantityRequest;
import com.ddagtech.aureliabooks.dto.response.ProductDetailResponse;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.CartService;
import com.ddagtech.aureliabooks.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class CartStorefrontTest {
    @Autowired MockMvc mvc;
    @MockitoBean ProductService products;
    @MockitoBean CartService carts;
    @MockitoBean CustomerIdentity identity;

    @BeforeEach
    void setup() {
        when(identity.resolve(any())).thenReturn(7L);
        when(products.viewDetail(1L)).thenReturn(product(200));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20, 99, 200})
    void productQuantityCannotExceedStockOrCartLimit(int stock) throws Exception {
        when(products.viewDetail(1L)).thenReturn(product(stock));

        String html = mvc.perform(get("/products/1").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var input = Pattern.compile("<input\\b[^>]*id=\"qtyInput\"[^>]*>").matcher(html);
        assertThat(input.find()).isTrue();
        assertThat(input.group()).contains("max=\"" + Math.min(CartQuantityRequest.MAX_QUANTITY, stock) + "\"");
        assertThat(input.group().contains("disabled")).isEqualTo(stock == 0);
        var increase = Pattern.compile("<button\\b[^>]*data-quantity-step=\"1\"[^>]*>").matcher(html);
        assertThat(increase.find()).isTrue();
        assertThat(increase.group().contains("disabled")).isEqualTo(stock <= 1);
    }

    @Test
    void badgeFailureShowsUnknownOnDesktopAndMobile() throws Exception {
        when(carts.countItems(7L)).thenThrow(new IllegalStateException("Database unavailable"));

        String html = mvc.perform(get("/products/1").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertBadges(html, "–");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 5})
    void badgeShowsPersistedQuantityIncludingEmptyCart(long quantity) throws Exception {
        when(carts.countItems(7L)).thenReturn(quantity);

        String html = mvc.perform(get("/products/1").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertBadges(html, String.valueOf(quantity));
    }

    @Test
    void guestBadgeStaysZeroWithoutReadingCart() throws Exception {
        String html = mvc.perform(get("/products/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertBadges(html, "0");

        verifyNoInteractions(carts, identity);
    }

    private void assertBadges(String html, String expected) {
        var links = Pattern.compile("<a\\b[^>]*href=\"/cart\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
        int count = 0;
        while (links.find()) {
            assertThat(links.group(1)).contains(">" + expected + "</span>");
            count++;
        }
        assertThat(count).isEqualTo(2);
    }

    private ProductDetailResponse product(int stock) {
        return new ProductDetailResponse(
                1L, "CART-QA", "Sách kiểm thử", new BigDecimal("25000"),
                new BigDecimal("25000"), stock, null, null, 200,
                Product.ProductType.BOOK, 1L, "Sách", null, null, null,
                List.of(), null, null, false, null, null, null,
                null, null, null, null, null, null, null);
    }
}
