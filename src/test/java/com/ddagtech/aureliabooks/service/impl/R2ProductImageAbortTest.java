package com.ddagtech.aureliabooks.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class R2ProductImageAbortTest {

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
            InputStream body,
            AtomicBoolean aborted) {

        GetObjectResponse metadata =
                GetObjectResponse.builder()
                        .contentType(contentType)
                        .contentLength(length)
                        .build();

        AbortableInputStream abortable =
                AbortableInputStream.create(
                        body,
                        () -> aborted.set(true)
                );

        return new ResponseInputStream<>(
                metadata,
                abortable
        );
    }

    @Test
    void validImageDoesNotAbort() {
        AtomicBoolean aborted = new AtomicBoolean(false);
        byte[] png = {1, 2, 3, 4};

        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(
                        "image/png",
                        (long) png.length,
                        new ByteArrayInputStream(png),
                        aborted
                ));

        assertThat(storage.read(IMAGE_ID)).isEqualTo(png);
        assertThat(aborted.get()).isFalse();
    }

    @Test
    void invalidContentTypeAborts() {
        AtomicBoolean aborted = new AtomicBoolean(false);

        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(
                        "text/html",
                        3L,
                        new ByteArrayInputStream(
                                new byte[]{1, 2, 3}
                        ),
                        aborted
                ));

        assertThatThrownBy(() -> storage.read(IMAGE_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(aborted.get()).isTrue();
    }

    @Test
    void oversizedDeclaredLengthAborts() {
        AtomicBoolean aborted = new AtomicBoolean(false);

        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(
                        "image/png",
                        (long) MAX_BYTES + 1,
                        new ByteArrayInputStream(new byte[]{1}),
                        aborted
                ));

        assertThatThrownBy(() -> storage.read(IMAGE_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(aborted.get()).isTrue();
    }

    @Test
    void oversizedBodyAborts() {
        AtomicBoolean aborted = new AtomicBoolean(false);

        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(
                        "image/png",
                        null,
                        new ByteArrayInputStream(
                                new byte[MAX_BYTES + 1]
                        ),
                        aborted
                ));

        assertThatThrownBy(() -> storage.read(IMAGE_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(aborted.get()).isTrue();
    }

    @Test
    void readIOExceptionAborts() {
        AtomicBoolean aborted = new AtomicBoolean(false);

        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Simulated read failure");
            }

            @Override
            public int read(byte[] b, int off, int len)
                    throws IOException {
                throw new IOException("Simulated read failure");
            }
        };

        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(
                        "image/png",
                        10L,
                        broken,
                        aborted
                ));

        assertThatThrownBy(() -> storage.read(IMAGE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);

        assertThat(aborted.get()).isTrue();
    }
}
