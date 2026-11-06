package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.util.ProductImageUrls;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class LocalProductImageStorageTest {
    @TempDir Path directory;

    @Test void storedImageSurvivesStorageRecreation() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB), "png", output);
        var storage = new LocalProductImageStorage(directory.toString(), new ProductImageProcessor());
        String url = ProductImageUrls.fromObjectKey(storage.store(
                new MockMultipartFile("file", "image.png", "image/png", output.toByteArray())));
        var reopened = new LocalProductImageStorage(directory.toString(), new ProductImageProcessor());
        var image = ImageIO.read(new java.io.ByteArrayInputStream(
                reopened.read(url.substring(ProductImageUrls.PREFIX.length()))));
        assertThat(image.getWidth()).isEqualTo(20);
        assertThat(image.getHeight()).isEqualTo(30);
    }

    @Test void rejectsInvalidContentAndTraversal() {
        var storage = new LocalProductImageStorage(directory.toString(), new ProductImageProcessor());
        assertThatThrownBy(() -> storage.store(new MockMultipartFile(
                "file", "fake.png", "image/png", new byte[]{1, 2, 3}))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> storage.read("../outside")).isInstanceOf(AppException.class);
    }
}
