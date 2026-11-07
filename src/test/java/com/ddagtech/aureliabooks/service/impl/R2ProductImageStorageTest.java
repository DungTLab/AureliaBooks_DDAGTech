package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

class R2ProductImageStorageTest {

    private S3Client s3Client;
    private ProductImageProcessor processor;
    private R2ProductImageStorage storage;
    private MockMultipartFile file;

    private final byte[] png = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x01, 0x02
    };

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        processor = mock(ProductImageProcessor.class);

        storage = new R2ProductImageStorage(
            s3Client,
            processor,
            "aureliabooks-staging-assets"
        );

        file = new MockMultipartFile(
            "file", "cover.png", "image/png",
            new byte[]{1, 2, 3}
        );
    }

    @Test
    void uploadsPngWithCorrectMetadataAndBytes()
            throws IOException {

        when(processor.toPng(file)).thenReturn(png);

        String key = storage.store(file);

        ArgumentCaptor<PutObjectRequest> requestCaptor =
            ArgumentCaptor.forClass(PutObjectRequest.class);

        ArgumentCaptor<RequestBody> bodyCaptor =
            ArgumentCaptor.forClass(RequestBody.class);

        verify(s3Client).putObject(
            requestCaptor.capture(),
            bodyCaptor.capture()
        );

        PutObjectRequest request = requestCaptor.getValue();
        RequestBody body = bodyCaptor.getValue();

        assertThat(request.bucket())
            .isEqualTo("aureliabooks-staging-assets");

        assertThat(request.key()).isEqualTo(key);
        assertThat(key).matches(
            "products/[0-9a-f-]{36}\\.png"
        );

        assertThat(request.contentType())
            .isEqualTo("image/png");

        assertThat(body.optionalContentLength())
            .contains((long) png.length);

        try (var stream = body.contentStreamProvider().newStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(png);
        }
    }

    @Test
    void generatesDifferentKeysForEachUpload() {
        when(processor.toPng(file)).thenReturn(png);

        String first = storage.store(file);
        String second = storage.store(file);

        assertThat(first).isNotEqualTo(second);

        verify(s3Client, times(2)).putObject(
            any(PutObjectRequest.class),
            any(RequestBody.class)
        );
    }

    @Test
    void invalidImageDoesNotReachS3() {
        when(processor.toPng(file)).thenThrow(
            new AppException(
                ErrorCode.INVALID_INPUT_DATA,
                "Invalid image"
            )
        );

        assertThatThrownBy(() -> storage.store(file))
            .isInstanceOf(AppException.class);

        verifyNoInteractions(s3Client);
    }

    @Test
    void s3FailureIsPropagatedSafely() {
        when(processor.toPng(file)).thenReturn(png);

        when(s3Client.putObject(
            any(PutObjectRequest.class),
            any(RequestBody.class)
        )).thenThrow(
            S3Exception.builder()
                .statusCode(503)
                .message("Mock S3 failure")
                .build()
        );

        assertThatThrownBy(() -> storage.store(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                "Cannot upload product image to R2"
            )
            .hasCauseInstanceOf(S3Exception.class);
    }

    @Test
    void blankBucketIsRejected() {
        assertThatThrownBy(() ->
            new R2ProductImageStorage(
                s3Client, processor, " "
            )
        ).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(s3Client);
    }
}
