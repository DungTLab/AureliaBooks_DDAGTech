package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.CartQuantityRequest;
import com.ddagtech.aureliabooks.dto.response.CartItemView;
import com.ddagtech.aureliabooks.dto.response.CartView;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.security.CustomAccessDeniedHandler;
import com.ddagtech.aureliabooks.service.AccountPresentationService;
import com.ddagtech.aureliabooks.service.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CartController.class)
@Import({SecurityConfig.class, CustomAccessDeniedHandler.class, com.ddagtech.aureliabooks.service.impl.AccountPresentationServiceImpl.class})
class CartControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CartService carts;
    @MockitoBean CustomerIdentity identity;
    @MockitoBean UserRepository users;

    @BeforeEach
    void setup() {
        when(identity.resolve(any())).thenReturn(7L);
        when(carts.viewCart(7L)).thenReturn(new CartView(List.of(new CartItemView(
                9L, 1L, "Sách kiểm thử", null, new BigDecimal("25000"), 3,
                new BigDecimal("75000"), 40, true, null)), new BigDecimal("75000"), 3));
    }

    @Test
    void cartPageRendersItemsAndQuantityForm() throws Exception {
        mvc.perform(get("/cart").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/view"))
                .andExpect(model().attribute("cartItemCount", 3))
                .andExpect(content().string(containsString("Sách kiểm thử")))
                .andExpect(content().string(containsString("/cart/items/9/quantity")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void loadFailureRenders503InsteadOfAnEmptyCartOrRedirectLoop() throws Exception {
        when(carts.viewCart(7L)).thenThrow(new IllegalStateException("Database unavailable"));
        mvc.perform(get("/cart").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("cart/view"))
                .andExpect(model().attributeExists("cartLoadError"))
                .andExpect(model().attributeDoesNotExist("cart"))
                .andExpect(content().string(containsString("Chưa thể tải giỏ hàng")))
                .andExpect(content().string(not(containsString("Giỏ hàng đang trống"))));
    }

    @Test
    void mutationsStillRedirectAndResolveOwnerOnTheServer() throws Exception {
        mvc.perform(post("/cart/items").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("productId", "1").param("quantity", "3").param("userId", "999"))
                .andExpect(redirectedUrl("/products/1")).andExpect(flash().attributeExists("successMessage"));
        mvc.perform(post("/cart/items/9/quantity").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("quantity", "2").param("userId", "999"))
                .andExpect(redirectedUrl("/cart")).andExpect(flash().attributeExists("successMessage"));
        mvc.perform(post("/cart/items/9/remove").with(user("customer").roles("CUSTOMER")).with(csrf()))
                .andExpect(redirectedUrl("/cart")).andExpect(flash().attributeExists("successMessage"));
        verify(carts).addItem(eq(7L), eq(1L), argThat(form -> form.getQuantity() == 3));
        verify(carts).updateQuantity(eq(7L), eq(9L), argThat(form -> form.getQuantity() == 2));
        verify(carts).removeItem(7L, 9L);
    }

    @Test
    void invalidQuantitiesNeverReachTheService() throws Exception {
        for (String quantity : List.of("", "abc", "1.5", "0", "11", "100")) {
            mvc.perform(post("/cart/items/9/quantity").with(user("customer").roles("CUSTOMER")).with(csrf())
                            .param("quantity", quantity))
                    .andExpect(redirectedUrl("/cart")).andExpect(flash().attributeExists("errorMessage"));
        }
        verify(carts, never()).updateQuantity(anyLong(), anyLong(), any(CartQuantityRequest.class));
    }

    @Test
    void saveAndBusinessFailuresKeepTheirFlashMessages() throws Exception {
        doThrow(new IllegalStateException("Write failed")).when(carts).updateQuantity(anyLong(), anyLong(), any());
        mvc.perform(post("/cart/items/9/quantity").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("quantity", "2"))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("errorMessage", "Không thể lưu thay đổi giỏ hàng. Vui lòng thử lại."));
        doThrow(new AppException(ErrorCode.INVALID_INPUT_DATA, "Không đủ tồn kho"))
                .when(carts).updateQuantity(anyLong(), anyLong(), any());
        mvc.perform(post("/cart/items/9/quantity").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("quantity", "2"))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("errorMessage", "Không đủ tồn kho"));
    }

    @Test
    void invalidAccountClearsPersistedAuthenticationAndRequiresLoginAgain() throws Exception {
        for (ErrorCode code : List.of(ErrorCode.UNAUTHENTICATED, ErrorCode.ACCOUNT_LOCKED, ErrorCode.USER_NOT_EXISTED)) {
            var context = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                    "customer", "unused", AuthorityUtils.createAuthorityList("ROLE_CUSTOMER")));
            var session = new MockHttpSession();
            session.setAttribute("SPRING_SECURITY_CONTEXT", context);
            doThrow(new AppException(code)).when(identity).resolve(any());
            mvc.perform(get("/cart").session(session))
                    .andExpect(redirectedUrl("/auth/login"))
                    .andExpect(flash().attribute("errorMessage", code.getMessage()));
            assertThat(context.getAuthentication()).isNull();
            mvc.perform(get("/cart").session(session)).andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost/auth/login"));
        }
        verifyNoInteractions(carts);
    }

    @Test
    void requestBindingErrorsRedirectWithMessage() throws Exception {
        mvc.perform(post("/cart/items").with(user("customer").roles("CUSTOMER")).with(csrf())
                        .param("quantity", "1"))
                .andExpect(redirectedUrl("/products")).andExpect(flash().attributeExists("errorMessage"));
        mvc.perform(post("/cart/items/not-a-number/remove").with(user("customer").roles("CUSTOMER")).with(csrf()))
                .andExpect(redirectedUrl("/cart")).andExpect(flash().attributeExists("errorMessage"));
        verifyNoInteractions(carts);
    }

    @Test
    void csrfAndCustomerRoleAreStillRequired() throws Exception {
        mvc.perform(post("/cart/items/9/remove").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/cart").with(user("staff").roles("SALE_STAFF")))
                .andExpect(redirectedUrl("/error/403"));
        mvc.perform(get("/cart")).andExpect(status().is3xxRedirection());
        verifyNoInteractions(carts, identity);
    }
    @Test
    void addKeepsTheCatalogueFilterAndRecommendationContext() throws Exception {
        mvc.perform(post("/cart/items").with(user("customer").roles("CUSTOMER")).with(csrf())
                .param("productId", "1").param("quantity", "1").param("returnTo", "/products?categoryId=2&page=3"))
                .andExpect(redirectedUrl("/products?categoryId=2&page=3"));
        mvc.perform(post("/cart/recommendations").with(user("customer").roles("CUSTOMER")).with(csrf())
                .param("productIds", "1", "2").param("returnTo", "/products/1"))
                .andExpect(redirectedUrl("/products/1"));
        verify(carts).addRecommendations(7L, List.of(1L, 2L));
    }

    @Test
    void addRejectsUntrustedDestinationsAndKeepsValidationErrorsOnProductPage() throws Exception {
        for (String target : List.of("https://evil.example", "//evil.example/products", "/admin/users",
                "/products/../admin", "/products?x=%0d%0aLocation:evil", "/products#unsafe")) {
            mvc.perform(post("/cart/items").with(user("customer").roles("CUSTOMER")).with(csrf())
                    .param("productId", "1").param("quantity", "1").param("returnTo", target))
                    .andExpect(redirectedUrl("/products/1"));
        }
        mvc.perform(post("/cart/items").with(user("customer").roles("CUSTOMER")).with(csrf())
                .param("productId", "1").param("quantity", "0"))
                .andExpect(redirectedUrl("/products/1")).andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void unavailableProductKeepsShoppingContext() throws Exception {
        doThrow(new AppException(ErrorCode.INVALID_INPUT_DATA, "Không đủ tồn kho"))
                .when(carts).addItem(anyLong(), anyLong(), any());
        mvc.perform(post("/cart/items").with(user("customer").roles("CUSTOMER")).with(csrf())
                .param("productId", "1").param("quantity", "1").param("returnTo", "/products/search?keyword=book"))
                .andExpect(redirectedUrl("/products/search?keyword=book"))
                .andExpect(flash().attribute("errorMessage", "Không đủ tồn kho"));
    }
}
