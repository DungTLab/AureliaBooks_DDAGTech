package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class R2ProductImageReadTest {

    private static final String IMAGE_ID =
            "550e8400-e29b-41d4-a716-446655440000";

    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private S3Client s3;
    private R2ProductImageStorage storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);

        storage = new R2ProductImageStorage(
                s3,
                mock(ProductImageProcessor.class),
                "aureliabooks-staging-assets"
        );
    }

    private ResponseInputStream<GetObjectResponse> response(
            String contentType,
            Long length,
            byte[] body) {

        GetObjectResponse metadata = GetObjectResponse.builder()
                .contentType(contentType)
                .contentLength(length)
                .build();

        return new ResponseInputStream<>(
                metadata,
                new ByteArrayInputStream(body)
        );
    }

    @Test
    void readsValidImageFromCorrectBucket() {
        byte[] png = {
                (byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A
        };

        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(
                        "image/png",
                        (long) png.length,
                        png
                ));

        assertThat(storage.read(IMAGE_ID)).isEqualTo(png);

        ArgumentCaptor<GetObjectRequest> captor =
                ArgumentCaptor.forClass(GetObjectRequest.class);

        verify(s3).getObject(captor.capture());

        assertThat(captor.getValue().bucket())
                .isEqualTo("aureliabooks-staging-assets");

        assertThat(captor.getValue().key())
                .isEqualTo("products/" + IMAGE_ID + ".png");
    }

    @Test
    void rejectsInvalidImageIdsWithoutCallingS3() {
        String[] invalid = {
                null,
                "",
                "../secret",
                "not-a-uuid",
                "550e8400-e29b-11d4-a716-446655440000"
        };

        for (String id : invalid) {
            assertThatThrownBy(() -> storage.read(id))
                    .isInstanceOf(AppException.class);
        }

        verifyNoInteractions(s3);
    }

    @Test
    void missingObjectReturnsNotFound() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder()
                        .statusCode(404)
                        .message("Object not found")
                        .build());

        assertThatThrownBy(() -> storage.read(IMAGE_ID))
                .isInstanceOf(AppException.class);
    }

    @Test
    void rejectsIncorrectContentType() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(
                        "text/html",
                        3L,
                        new byte[]{1, 2, 3}
                ));

        assertThatThrownBy(() -> storage.read(IMAGE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Invalid stored product image metadata"
                );
    }

    @Test
    void rejectsOversizedDeclaredContentLength() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(
                        "image/png",
                        (long) MAX_BYTES + 1,
                        new byte[]{1, 2, 3}
                ));

        assertThatThrownBy(() -> storage.read(IMAGE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Invalid stored product image metadata"
                );
    }

    @Test
    void rejectsOversizedResponseBody() {
        byte[] oversized = new byte[MAX_BYTES + 1];

        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(
                        "image/png",
                        null,
                        oversized
                ));

        assertThatThrownBy(() -> storage.read(IMAGE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Invalid stored product image size"
                );
    }

    @Test
    void rejectsTruncatedResponseBody() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(
                        "image/png",
                        10L,
                        new byte[]{1, 2, 3}
                ));

        assertThatThrownBy(() -> storage.read(IMAGE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Invalid stored product image size"
                );
    }

    @Test
    void handlesS3ServiceFailure() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder()
                        .statusCode(503)
                        .message("Service unavailable")
                        .build());

        assertThatThrownBy(() -> storage.read(IMAGE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Cannot read product image from R2"
                )
                .hasCauseInstanceOf(S3Exception.class);
    }
}
