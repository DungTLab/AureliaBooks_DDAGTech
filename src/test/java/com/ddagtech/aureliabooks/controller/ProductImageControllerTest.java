package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.exception.GlobalExceptionHandler;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.ProductImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProductImageControllerTest {

    private static final String ID =
            "550e8400-e29b-41d4-a716-446655440000";

    private static final String URL =
            "/products/images/" + ID;

    private ProductRepository products;
    private ProductImageStorage storage;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        storage = mock(ProductImageStorage.class);

        mvc = MockMvcBuilders.standaloneSetup(
                new ProductImageController(products, storage)
        ).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void visibleImageReturnsPng() throws Exception {
        byte[] png = {
                (byte) 0x89, 0x50, 0x4E, 0x47
        };

        when(products.countVisibleProductsByMainImageUrl(URL))
                .thenReturn(1L);

        when(storage.read(ID)).thenReturn(png);

        mvc.perform(get(URL).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png))
                .andExpect(header().string(
                        "Cache-Control", "no-store"))
                .andExpect(header().string(
                        "X-Content-Type-Options", "nosniff"));

        verify(storage).read(ID);
    }

    @Test
    void invalidIdReturns404WithoutDatabaseAccess()
            throws Exception {

        mvc.perform(get("/products/images/not-a-uuid")
                        .accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"));

        verifyNoInteractions(products, storage);
    }

    @Test
    void unassignedImageReturns404WithoutS3Access()
            throws Exception {

        when(products.countVisibleProductsByMainImageUrl(URL))
                .thenReturn(0L);

        mvc.perform(get(URL).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"));

        verifyNoInteractions(storage);
    }

    @Test
    void missingR2ObjectReturns404() throws Exception {
        when(products.countVisibleProductsByMainImageUrl(URL))
                .thenReturn(1L);

        when(storage.read(ID)).thenThrow(
                new AppException(ErrorCode.RESOURCE_NOT_FOUND)
        );

        mvc.perform(get(URL).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void r2FailureReturns503() throws Exception {
        when(products.countVisibleProductsByMainImageUrl(URL))
                .thenReturn(1L);

        when(storage.read(ID)).thenThrow(
                new IllegalStateException("Mock R2 unavailable")
        );

        mvc.perform(get(URL).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().bytes(new byte[0]))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void databaseFailureReturns503WithoutS3Access()
            throws Exception {

        when(products.countVisibleProductsByMainImageUrl(URL))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "Mock database unavailable"
                        )
                );

        mvc.perform(get(URL).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().bytes(new byte[0]))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"));

        verifyNoInteractions(storage);
    }
}
