package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.service.ProductImageStorage;
import com.ddagtech.aureliabooks.util.ProductImageUrls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Slf4j
@RestController
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${app.r2.enabled:false} && ${app.r2.upload-enabled:true}")
public class ManagerProductImageController {
    private final ProductImageStorage storage;
    public record UploadedImage(String imageUrl) { }
    public record UploadError(String message) { }

    @PreAuthorize("hasRole('MANAGER')")
    @PostMapping(value = "/manager/products/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UploadedImage> upload(@RequestPart("file") MultipartFile file) {
        String url = ProductImageUrls.fromObjectKey(storage.store(file));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(new UploadedImage(url));
    }

    @ExceptionHandler(AppException.class)
    ResponseEntity<UploadError> invalid(AppException error) {
        return failure(error.getErrorCode() == ErrorCode.INVALID_INPUT_DATA
                ? HttpStatus.BAD_REQUEST : HttpStatus.SERVICE_UNAVAILABLE,
                error.getErrorCode() == ErrorCode.INVALID_INPUT_DATA
                        ? "Chọn ảnh PNG/JPEG hợp lệ, tối đa 5 MiB và 16 triệu pixel."
                        : "Không thể tải ảnh lên lúc này. Vui lòng thử lại sau.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<UploadError> missingFile() {
        return failure(HttpStatus.BAD_REQUEST, "Vui lòng chọn file ảnh.");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<UploadError> storageFailure(IllegalStateException error) {
        log.warn("Product image upload failed", error);
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "Không thể tải ảnh lên lúc này. Vui lòng thử lại sau.");
    }

    private ResponseEntity<UploadError> failure(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(new UploadError(message));
    }
}
