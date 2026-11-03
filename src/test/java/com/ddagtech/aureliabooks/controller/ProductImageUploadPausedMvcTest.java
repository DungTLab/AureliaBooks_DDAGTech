package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.admin.ManagerProductImageController;
import com.ddagtech.aureliabooks.controller.admin.AdminProductController;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.ProductImageStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ProductImageController.class, ManagerProductImageController.class, AdminProductController.class},
        properties = {"app.r2.enabled=true", "app.r2.upload-enabled=false"})
@Import(SecurityConfig.class)
class ProductImageUploadPausedMvcTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @MockitoBean ProductRepository products;
    @MockitoBean ProductImageStorage storage;
    @MockitoBean com.ddagtech.aureliabooks.service.AdminProductService adminProducts;
    @MockitoBean com.ddagtech.aureliabooks.service.MasterDataService masterData;

    @Test void pausedFormsHideUploaderButKeepManualUrl() throws Exception {
        mvc.perform(get("/manager/books/new").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("manager").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-image-uploader"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"mainImageUrl\" type=\"text\"")));
    }

    @Test void pauseRemovesUploadControllerButPreservesAnonymousReads() throws Exception {
        assertThat(context.getBeansOfType(ManagerProductImageController.class)).isEmpty();
        assertThat(context.getBeansOfType(ProductImageController.class)).hasSize(1);
        String id = "550e8400-e29b-41d4-a716-446655440000";
        String url = "/products/images/" + id;
        when(products.countVisibleProductsByMainImageUrl(url)).thenReturn(1L);
        when(storage.read(id)).thenReturn(new byte[] {1, 2});
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(post("/manager/products/images")).andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Location"));
        verify(storage, never()).store(any());
    }
}
