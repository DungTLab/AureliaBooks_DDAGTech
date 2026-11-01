package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class ProductImageProcessorTest {

    private final ProductImageProcessor processor =
            new ProductImageProcessor();

    private MockMultipartFile createImage(
            String format,
            int width,
            int height) throws IOException {

        int type = format.equals("jpeg")
                ? BufferedImage.TYPE_INT_RGB
                : BufferedImage.TYPE_INT_ARGB;

        BufferedImage image =
                new BufferedImage(width, height, type);

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        assertThat(ImageIO.write(image, format, output)).isTrue();

        return new MockMultipartFile(
                "file",
                "cover." + format,
                format.equals("jpeg") ? "image/jpeg" : "image/png",
                output.toByteArray()
        );
    }

    @Test
    void acceptsValidPng() throws IOException {
        byte[] result = processor.toPng(
                createImage("png", 640, 480)
        );

        BufferedImage decoded = ImageIO.read(
                new ByteArrayInputStream(result)
        );

        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(640);
        assertThat(decoded.getHeight()).isEqualTo(480);
    }

    @Test
    void convertsJpegToPng() throws IOException {
        byte[] result = processor.toPng(
                createImage("jpeg", 800, 600)
        );

        assertThat(result).startsWith(
                (byte) 0x89,
                (byte) 0x50,
                (byte) 0x4E,
                (byte) 0x47
        );

        BufferedImage decoded = ImageIO.read(
                new ByteArrayInputStream(result)
        );

        assertThat(decoded.getWidth()).isEqualTo(800);
        assertThat(decoded.getHeight()).isEqualTo(600);
    }

    @Test
    void resizesLargeImage() throws IOException {
        byte[] result = processor.toPng(
                createImage("png", 2400, 1200)
        );

        BufferedImage decoded = ImageIO.read(
                new ByteArrayInputStream(result)
        );

        assertThat(decoded.getWidth()).isEqualTo(1600);
        assertThat(decoded.getHeight()).isEqualTo(800);
    }

    @Test
    void rejectsFakeImage() {
        MockMultipartFile fake = new MockMultipartFile(
                "file",
                "fake.png",
                "image/png",
                "not an image".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> processor.toPng(fake))
                .isInstanceOf(AppException.class);
    }

    @Test
    void rejectsOversizedFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "large.png",
                "image/png",
                new byte[5 * 1024 * 1024 + 1]
        );

        assertThatThrownBy(() -> processor.toPng(file))
                .isInstanceOf(AppException.class);
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "empty.png",
                "image/png",
                new byte[0]
        );

        assertThatThrownBy(() -> processor.toPng(file))
                .isInstanceOf(AppException.class);
    }

    @Test
    void rejectsNullFile() {
        assertThatThrownBy(() -> processor.toPng(null))
                .isInstanceOf(AppException.class);
    }
}
