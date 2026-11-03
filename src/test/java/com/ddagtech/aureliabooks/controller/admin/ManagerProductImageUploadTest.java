package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.security.CustomAccessDeniedHandler;
import com.ddagtech.aureliabooks.security.CustomAuthenticationEntryPoint;
import com.ddagtech.aureliabooks.service.impl.ProductImageProcessor;
import com.ddagtech.aureliabooks.service.impl.R2ProductImageStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ManagerProductImageController.class,
        properties = {"app.r2.enabled=true", "app.r2.bucket=test-bucket"})
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        R2ProductImageStorage.class, ProductImageProcessor.class})
class ManagerProductImageUploadTest {
    @Autowired MockMvc mvc;
    @MockitoBean S3Client s3;
    @MockitoBean(name = "multipartResolver") org.springframework.web.multipart.MultipartResolver multipartResolver;
    private static final String API = "/manager/products/images";

    private MockMultipartFile png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", output);
        return new MockMultipartFile("file", "../../unsafe.svg", "text/plain", output.toByteArray());
    }

    @Test void managerUploadsActualPngAndReceivesCanonicalUrl() throws Exception {
        mvc.perform(multipart(API).file(png()).with(user("manager").roles("MANAGER")).with(csrf()))
                .andExpect(status().isCreated()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.imageUrl").value(org.hamcrest.Matchers.matchesPattern(
                        "/products/images/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Location"));
        var request = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = org.mockito.ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        try (var stream = body.getValue().contentStreamProvider().newStream()) {
            assertThat(ImageIO.read(stream).getHeight()).isEqualTo(3);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "SALE_STAFF", "CUSTOMER"})
    void otherRolesCannotUpload(String role) throws Exception {
        mvc.perform(multipart(API).file(png()).with(user("other").roles(role)).with(csrf())
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(s3);
    }

    @Test void anonymousCannotUpload() throws Exception {
        mvc.perform(multipart(API).file(png()).with(csrf()).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(s3);
    }

    @Test void missingCsrfIsRejected() throws Exception {
        mvc.perform(multipart(API).file(png()).with(user("manager").roles("MANAGER"))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(s3);
    }

    @Test void invalidCsrfIsRejected() throws Exception {
        mvc.perform(multipart(API).file(png()).with(user("manager").roles("MANAGER")).with(csrf().useInvalidToken())
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(s3);
    }

    @Test void missingFileIs400WithoutRedirect() throws Exception {
        mvc.perform(multipart(API).with(user("manager").roles("MANAGER")).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(s3);
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "fake", "large"})
    void invalidFilesNeverReachS3(String input) throws Exception {
        byte[] bytes = switch (input) {
            case "empty" -> new byte[0];
            case "large" -> new byte[5 * 1024 * 1024 + 1];
            default -> "<svg onload='alert(1)'>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        };
        mvc.perform(multipart(API).file(new MockMultipartFile("file", "fake.png", "image/png", bytes))
                        .with(user("manager").roles("MANAGER")).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(s3);
    }

    @ParameterizedTest @ValueSource(strings = {"large", "malformed"})
    void multipartParsingFailureReturnsJsonBeforeControllerDispatch(String input) throws Exception {
        when(multipartResolver.isMultipart(any())).thenReturn(true);
        org.springframework.web.multipart.MultipartException failure = input.equals("large")
                ? new org.springframework.web.multipart.MaxUploadSizeExceededException(5 * 1024 * 1024)
                : new org.springframework.web.multipart.MultipartException("Mock malformed boundary");
        when(multipartResolver.resolveMultipart(any())).thenThrow(failure);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(API)
                        .contentType("multipart/form-data; boundary=test")
                        .with(user("manager").roles("MANAGER")).with(csrf()))
                .andExpect(status().is(input.equals("large") ? 413 : 400))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(s3);
    }

    @Test void storageOutageIs503WithoutLeakingCause() throws Exception {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("private diagnostic").build());
        mvc.perform(multipart(API).file(png()).with(user("manager").roles("MANAGER")).with(csrf()))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private diagnostic"))));
    }
}
