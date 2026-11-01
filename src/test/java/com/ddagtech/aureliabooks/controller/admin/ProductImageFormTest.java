package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.request.BookForm;
import com.ddagtech.aureliabooks.dto.request.StationeryForm;
import com.ddagtech.aureliabooks.service.AdminProductService;
import com.ddagtech.aureliabooks.service.MasterDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = AdminProductController.class, properties = "app.r2.enabled=true")
@Import(SecurityConfig.class)
class ProductImageFormTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminProductService products;
    @MockitoBean MasterDataService masterData;
    private static final String URL = "/products/images/550e8400-e29b-41d4-a716-446655440000";

    @ParameterizedTest @ValueSource(strings = {"books", "stationeries"})
    void bothFormsRenderUploaderWithCsrfAndManualUrl(String type) throws Exception {
        String html = mvc.perform(get("/manager/" + type + "/new").with(user("manager").roles("MANAGER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"mainImageUrl\" type=\"text\"", "data-upload-url=\"/manager/products/images\"",
                "data-header=\"X-CSRF-TOKEN\"", "/js/product-image-upload.js");
        var token = java.util.regex.Pattern.compile("<input[^>]*data-image-csrf[^>]*>").matcher(html);
        assertThat(token.find()).isTrue();
        assertThat(token.group()).containsPattern("value=\"[^\"]+\"");
    }

    @Test void bookFormSubmitsCanonicalInternalUrl() throws Exception {
        mvc.perform(post("/manager/books").with(user("manager").roles("MANAGER")).with(csrf())
                .param("barcode", "893500000001").param("title", "Book").param("categoryId", "1")
                .param("price", "10").param("originalCost", "1").param("isbn", "080442957X")
                .param("publisherId", "1").param("authorIds", "1").param("mainImageUrl", "  " + URL + "  "))
                .andExpect(status().is3xxRedirection());
        var form = org.mockito.ArgumentCaptor.forClass(BookForm.class);
        verify(products).createBook(form.capture());
        assertThat(form.getValue().mainImageUrl()).isEqualTo(URL);
    }

    @Test void stationeryFormSubmitsExternalUrlUnchanged() throws Exception {
        mvc.perform(post("/manager/stationeries").with(user("manager").roles("MANAGER")).with(csrf())
                .param("barcode", "893500000001").param("title", "Pen").param("categoryId", "1")
                .param("price", "10").param("originalCost", "1").param("brandId", "1")
                .param("mainImageUrl", "https://example.com/old.png?x=1&y=2"))
                .andExpect(status().is3xxRedirection());
        var form = org.mockito.ArgumentCaptor.forClass(StationeryForm.class);
        verify(products).createStationery(form.capture());
        assertThat(form.getValue().mainImageUrl()).isEqualTo("https://example.com/old.png?x=1&y=2");
    }

    @ParameterizedTest @ValueSource(strings = {"books", "stationeries"})
    void validationErrorRetainsUploadedUrlForRetry(String type) throws Exception {
        mvc.perform(post("/manager/" + type).with(user("manager").roles("MANAGER")).with(csrf())
                        .param("mainImageUrl", URL))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"" + URL + "\"")));
        verifyNoInteractions(products);
    }
}
