package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.service.impl.AvatarStorageImpl;
import com.ddagtech.aureliabooks.service.impl.ProductImageProcessor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class R2AvatarStorageTest {
    @TempDir Path temp;
    private final S3Client client = mock(S3Client.class);

    @SuppressWarnings("unchecked")
    private AvatarStorageImpl storage(boolean uploads) {
        ObjectProvider<S3Client> clients = mock(ObjectProvider.class);
        when(clients.getIfAvailable()).thenReturn(client);
        return new AvatarStorageImpl(temp.resolve("legacy").toString(), new ProductImageProcessor(), clients,
            "test-bucket", true, uploads);
    }

    private MockMultipartFile image() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("avatar", "../../image.png", "image/png", bytes.toByteArray());
    }

    @Test void uploadsNormalizedAvatarToR2AndReadsThroughPrivateUrlWithoutLocalWrite() throws Exception {
        var storage = storage(true);
        String url = storage.store(image());
        assertThat(url).matches("/profile/avatar/[0-9a-f-]{36}\\.png");
        var put = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(put.capture(), body.capture());
        String filename = url.substring("/profile/avatar/".length());
        assertThat(put.getValue().key()).isEqualTo("avatars/" + filename);
        assertThat(put.getValue().bucket()).isEqualTo("test-bucket");
        byte[] normalized = body.getValue().contentStreamProvider().newStream().readAllBytes();
        assertThat(ImageIO.read(new ByteArrayInputStream(normalized)).getWidth()).isEqualTo(512);
        assertThat(Files.exists(temp.resolve("legacy"))).isFalse();
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
            GetObjectResponse.builder().contentType("image/png").contentLength((long) normalized.length).build(),
            AbortableInputStream.create(new ByteArrayInputStream(normalized))));
        assertThat(storage.read(filename)).isEqualTo(normalized);
        storage.delete(url);
        verify(client).deleteObject(argThat((DeleteObjectRequest request) -> request.key().equals("avatars/" + filename)));
    }

    @Test void disabledUploadsAndOversizedFilesNeverCallR2() throws Exception {
        assertThatThrownBy(() -> storage(false).store(image())).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> storage(true).store(new MockMultipartFile("avatar", "big.png", "image/png", new byte[2 * 1024 * 1024 + 1])))
            .isInstanceOf(AppException.class);
        verifyNoInteractions(client);
    }

    @Test void uploadFailureIsReportedAndDoesNotReturnASavedUrl() throws Exception {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(S3Exception.builder().statusCode(403).message("private diagnostic").build());
        assertThatThrownBy(() -> storage(true).store(image())).isInstanceOf(AppException.class)
            .hasMessageContaining("Không thể lưu").hasMessageNotContaining("private diagnostic");
    }

    @Test void legacyLocalAvatarIsReadableOnR2NotFoundAndTraversalNeverCallsR2() throws Exception {
        String filename = "550e8400-e29b-41d4-a716-446655440000.png";
        Path legacy = Files.createDirectories(temp.resolve("legacy"));
        Files.write(legacy.resolve(filename), new byte[]{1, 2, 3});
        when(client.getObject(any(GetObjectRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(404).build());
        var storage = storage(true);
        assertThat(storage.read(filename)).containsExactly(1, 2, 3);
        clearInvocations(client);
        assertThatThrownBy(() -> storage.read("../../private.png")).isInstanceOf(AppException.class);
        verifyNoInteractions(client);
    }
}
