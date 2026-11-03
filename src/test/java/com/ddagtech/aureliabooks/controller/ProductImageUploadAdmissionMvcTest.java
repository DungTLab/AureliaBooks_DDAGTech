package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.admin.ManagerProductImageController;
import com.ddagtech.aureliabooks.security.ProductImageUploadFilter;
import com.ddagtech.aureliabooks.service.ProductImageStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ManagerProductImageController.class,
        properties = {"app.r2.enabled=true", "app.r2.upload-requests-per-minute=2"})
@Import(SecurityConfig.class)
class ProductImageUploadAdmissionMvcTest {
    @Autowired MockMvc mvc;
    @Autowired @Qualifier("internalSecurityFilterChain") SecurityFilterChain chain;
    @MockitoBean ProductImageStorage storage;
    @Test void quotaRunsBeforeCsrfAndRejectsThirdUploadWithoutStorageCall() throws Exception {
        var types = chain.getFilters().stream().map(Object::getClass).toList();
        assertThat(types.indexOf(ProductImageUploadFilter.class)).isLessThan(types.indexOf(CsrfFilter.class));
        when(storage.store(any())).thenReturn("products/550e8400-e29b-41d4-a716-446655440000.png");
        for (int i = 0; i < 2; i++) {
            mvc.perform(multipart("/manager/products/images").file(new MockMultipartFile("file", new byte[]{1}))
                    .with(user("quota-manager").roles("MANAGER")).with(csrf())).andExpect(status().isCreated());
        }
        mvc.perform(multipart("/manager/products/images").file(new MockMultipartFile("file", new byte[]{1}))
                .with(user("quota-manager").roles("MANAGER")).with(csrf()))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                .andExpect(header().doesNotExist("Location"));
        verify(storage, times(2)).store(any());
    }
}
