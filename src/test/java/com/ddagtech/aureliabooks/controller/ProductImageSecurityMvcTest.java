package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.security.CustomAccessDeniedHandler;
import com.ddagtech.aureliabooks.security.CustomAuthenticationEntryPoint;
import com.ddagtech.aureliabooks.service.ProductImageStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC security filters and handlers; no datasource, JPA or S3 configuration. */
@WebMvcTest(controllers = ProductImageController.class, properties = "app.r2.enabled=true")
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
class ProductImageSecurityMvcTest {
    private static final String ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String URL = "/products/images/" + ID;
    @Autowired MockMvc mvc;
    @Autowired FilterChainProxy filters;
    @Autowired @Qualifier("internalSecurityFilterChain") SecurityFilterChain internal;
    @Autowired @Qualifier("securityFilterChain") SecurityFilterChain storefront;
    @MockitoBean ProductRepository products;
    @MockitoBean ProductImageStorage storage;

    @Test void realChainsAreOrderedAndSelectCorrectPortal() {
        assertThat(filters.getFilterChains()).containsExactly(internal, storefront);
        assertFirstChain(URL, storefront);
        assertFirstChain("/profile", storefront);
        assertFirstChain("/manager/books", internal);
        assertThat(internal.matches(new MockHttpServletRequest("GET", URL))).isFalse();
        assertThat(internal.getFilters()).anyMatch(CsrfFilter.class::isInstance);
        assertThat(storefront.getFilters()).anyMatch(CsrfFilter.class::isInstance);
        verifyNoInteractions(products, storage);
    }

    @Test void anonymousVisibleImageReturnsPngWithoutCsrf() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47};
        when(products.countVisibleProductsByMainImageUrl(URL)).thenReturn(1L);
        when(storage.read(ID)).thenReturn(png);
        imageHeaders(mvc.perform(get(URL)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png));
        verify(storage).read(ID);
    }

    @Test void invalidUuidReturns404BeforeDatabase() throws Exception {
        emptyError(mvc.perform(get("/products/images/invalid")), 404);
        verifyNoInteractions(products, storage);
    }

    @Test void orphanImageReturns404BeforeStorage() throws Exception {
        when(products.countVisibleProductsByMainImageUrl(URL)).thenReturn(0L);
        emptyError(mvc.perform(get(URL)), 404);
        verifyNoInteractions(storage);
    }

    @Test void missingObjectReturns404() throws Exception {
        when(products.countVisibleProductsByMainImageUrl(URL)).thenReturn(1L);
        when(storage.read(ID)).thenThrow(new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        emptyError(mvc.perform(get(URL)), 404);
        verify(storage).read(ID);
    }

    @Test void storageFailureReturns503() throws Exception {
        when(products.countVisibleProductsByMainImageUrl(URL)).thenReturn(1L);
        when(storage.read(ID)).thenThrow(new IllegalStateException("Mock storage outage"));
        emptyError(mvc.perform(get(URL)), 503);
    }

    @Test void databaseFailureReturns503BeforeStorage() throws Exception {
        when(products.countVisibleProductsByMainImageUrl(URL))
                .thenThrow(new DataAccessResourceFailureException("Mock database outage"));
        emptyError(mvc.perform(get(URL)), 503);
        verifyNoInteractions(storage);
    }

    @Test void anonymousManagerPageUsesInternalLogin() throws Exception {
        mvc.perform(get("/manager/books")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/internal/login"));
        verifyNoInteractions(products, storage);
    }

    @Test void anonymousProfileUsesStorefrontLogin() throws Exception {
        mvc.perform(get("/profile")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/auth/login"));
        verifyNoInteractions(products, storage);
    }

    // No POST image handler exists yet. This verifies CSRF rejection in each chain,
    // not upload authorization or a successful upload (reserved for R2.31).
    @ParameterizedTest
    @ValueSource(strings = {"/manager/products/images", "/products/images"})
    void postWithoutCsrfIsRejectedInBothChains(String path) throws Exception {
        mvc.perform(post(path).with(user("manager").roles("MANAGER")))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(forwardedUrl("/error/403"));
        verifyNoInteractions(products, storage);
    }

    private void assertFirstChain(String path, SecurityFilterChain expected) {
        var request = new MockHttpServletRequest("GET", path);
        assertThat(filters.getFilterChains().stream().filter(chain -> chain.matches(request)).findFirst())
                .contains(expected);
    }

    private ResultActions imageHeaders(ResultActions result) throws Exception {
        return result.andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Location"));
    }

    private void emptyError(ResultActions result, int status) throws Exception {
        imageHeaders(result).andExpect(status().is(status))
                .andExpect(content().bytes(new byte[0]));
    }
}
