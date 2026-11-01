package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.util.ProductImageUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Verify new internal references before DB writes; never delete objects in a DB transaction. */
@Service
@RequiredArgsConstructor
public class ProductImageReferencePolicy {
    private final ObjectProvider<ProductImageStorage> storage;

    public void validateForSave(String requested, String current) {
        String url = ProductImageUrls.normalize(requested);
        // Preserve existing links (including legacy formats) without contacting R2 on edits.
        if (Objects.equals(url, ProductImageUrls.normalize(current))) return;
        ProductImageUrls.validate(url);
        if (!ProductImageUrls.isInternal(url)) return; // External URLs are never fetched by the server.
        ProductImageStorage images = storage.getIfAvailable();
        if (images == null) throw unavailable();
        try {
            images.read(url.substring(ProductImageUrls.PREFIX.length()));
        } catch (AppException e) {
            if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                        "Ảnh tải lên không tồn tại. Vui lòng tải lại ảnh.");
            }
            throw unavailable();
        } catch (IllegalStateException e) {
            throw unavailable();
        }
    }

    private AppException unavailable() {
        return new AppException(ErrorCode.PRODUCT_IMAGE_UNAVAILABLE);
    }
}
