package com.ddagtech.aureliabooks.service;

import org.springframework.web.multipart.MultipartFile;

public interface ProductImageStorage {

    /**
     * Validates, normalizes and stores a product image.
     *
     * @return R2 object key, not a public URL.
     */
    String store(MultipartFile file);

    /**
     * Reads a previously stored product image by its UUID.
     */
    byte[] read(String imageId);
}
