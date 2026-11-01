package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
public class ProductImageProcessor {

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;
    private static final int MAX_DIMENSION = 1600;

    public byte[] toPng(MultipartFile file) {
        if (file == null || file.isEmpty()
                || file.getSize() > MAX_FILE_SIZE) {
            throw invalid("Ảnh sản phẩm phải có dung lượng từ 1 byte đến 5 MB.");
        }

        try (var stream = file.getInputStream();
             var imageInput = ImageIO.createImageInputStream(stream)) {

            if (imageInput == null) {
                throw invalid("Không thể đọc file ảnh.");
            }

            var readers = ImageIO.getImageReaders(imageInput);

            if (!readers.hasNext()) {
                throw invalid("Chỉ hỗ trợ ảnh PNG hoặc JPEG hợp lệ.");
            }

            ImageReader reader = readers.next();

            try {
                reader.setInput(imageInput, true, true);

                String format = reader.getFormatName();

                if (!format.equalsIgnoreCase("png")
                        && !format.equalsIgnoreCase("jpeg")) {
                    throw invalid("Chỉ hỗ trợ ảnh PNG hoặc JPEG.");
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width <= 0 || height <= 0
                        || (long) width * height > MAX_PIXELS) {
                    throw invalid("Ảnh vượt quá giới hạn 16 triệu pixel.");
                }

                BufferedImage original = reader.read(0);

                double scale = Math.min(
                        1.0,
                        (double) MAX_DIMENSION / Math.max(width, height)
                );

                int targetWidth = Math.max(1, (int) (width * scale));
                int targetHeight = Math.max(1, (int) (height * scale));

                BufferedImage normalized = new BufferedImage(
                        targetWidth,
                        targetHeight,
                        BufferedImage.TYPE_INT_ARGB
                );

                Graphics2D graphics = normalized.createGraphics();

                try {
                    graphics.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    );

                    graphics.drawImage(
                            original,
                            0,
                            0,
                            targetWidth,
                            targetHeight,
                            null
                    );
                } finally {
                    graphics.dispose();
                }

                ByteArrayOutputStream output = new ByteArrayOutputStream();

                if (!ImageIO.write(normalized, "png", output)) {
                    throw new IOException("PNG encoder unavailable");
                }

                byte[] result = output.toByteArray();

                if (result.length == 0 || result.length > MAX_FILE_SIZE) {
                    throw invalid("Ảnh sau xử lý vượt quá dung lượng cho phép.");
                }

                return result;

            } finally {
                reader.dispose();
            }

        } catch (IOException e) {
            throw invalid("Không thể xử lý ảnh sản phẩm.");
        }
    }

    private AppException invalid(String message) {
        return new AppException(
                ErrorCode.INVALID_INPUT_DATA,
                message
        );
    }
}
