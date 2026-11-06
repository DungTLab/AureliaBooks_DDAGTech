package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.service.ProductImageStorage;
import com.ddagtech.aureliabooks.util.ProductImageUrls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Local persistent storage when remote product storage is disabled. */
@Service
@ConditionalOnProperty(name = "app.r2.enabled", havingValue = "false", matchIfMissing = true)
public class LocalProductImageStorage implements ProductImageStorage {
    private final Path root;
    private final ProductImageProcessor processor;

    public LocalProductImageStorage(
            @Value("${app.product-images.directory:./uploads/products}") String directory,
            ProductImageProcessor processor) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.processor = processor;
    }

    @Override
    public String store(MultipartFile file) {
        byte[] image = processor.toPng(file);
        String id = UUID.randomUUID().toString();
        Path target = path(id);
        try {
            Files.createDirectories(root);
            Files.write(target, image, java.nio.file.StandardOpenOption.CREATE_NEW);
            return "products/" + id + ".png";
        } catch (IOException e) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            throw new AppException(ErrorCode.PRODUCT_IMAGE_UNAVAILABLE);
        }
    }

    @Override
    public byte[] read(String imageId) {
        Path target = path(imageId);
        try {
            return Files.readAllBytes(target);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        } catch (IOException e) {
            throw new AppException(ErrorCode.PRODUCT_IMAGE_UNAVAILABLE);
        }
    }

    private Path path(String id) {
        if (!ProductImageUrls.isInternal(ProductImageUrls.PREFIX + id)) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return root.resolve(id + ".png");
    }
}
