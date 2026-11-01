package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.ProductImageStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

@Slf4j
@RestController
@ConditionalOnProperty(
        prefix = "app.r2",
        name = "enabled",
        havingValue = "true"
)
public class ProductImageController {

    private static final Pattern IMAGE_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    );

    private final ProductRepository products;
    private final ProductImageStorage storage;

    public ProductImageController(
            ProductRepository products,
            ProductImageStorage storage) {
        this.products = products;
        this.storage = storage;
    }

    @GetMapping(
            value = "/products/images/{imageId}",
            produces = MediaType.IMAGE_PNG_VALUE
    )
    public ResponseEntity<byte[]> getImage(
            @PathVariable String imageId) {

        if (imageId == null
                || !IMAGE_ID.matcher(imageId).matches()) {
            return error(HttpStatus.NOT_FOUND);
        }

        String imageUrl = "/products/images/" + imageId;

        if (products.countVisibleProductsByMainImageUrl(imageUrl) <= 0) {
            return error(HttpStatus.NOT_FOUND);
        }

        byte[] image = storage.read(imageId);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(image);
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<byte[]> handleBusinessError(
            AppException exception) {

        if (exception.getErrorCode()
                == ErrorCode.RESOURCE_NOT_FOUND) {
            return error(HttpStatus.NOT_FOUND);
        }

        log.warn(
                "Product image business error: {}",
                exception.getErrorCode()
        );

        return error(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> handleUnexpectedError(
            Exception exception) {

        log.error("Unable to serve product image", exception);

        return error(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private ResponseEntity<byte[]> error(HttpStatus status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }
}
