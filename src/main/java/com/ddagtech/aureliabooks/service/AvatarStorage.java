package com.ddagtech.aureliabooks.service;

import org.springframework.web.multipart.MultipartFile;

/** Application contract for AvatarStorage; implementation lives in service.impl. */
public interface AvatarStorage {

    String store(MultipartFile file);

    byte[] read(String filename);

    void delete(String url);
}
