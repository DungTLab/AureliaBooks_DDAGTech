package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.service.AvatarStorage;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Private avatar URLs remain stable across local storage and R2. */
@Slf4j
@Service
public class AvatarStorageImpl implements AvatarStorage {
    private static final String PREFIX = "/profile/avatar/";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Path root;
    private final ProductImageProcessor images;
    private final S3Client r2;
    private final String bucket;
    private final boolean uploadsEnabled;

    public AvatarStorageImpl(String directory) {
        this(directory, new ProductImageProcessor(), null, "", false, true);
    }

    @Autowired
    public AvatarStorageImpl(@Value("${app.avatar.directory:./uploads/avatars}") String directory,
            ProductImageProcessor images, ObjectProvider<S3Client> clients,
            @Value("${app.r2.bucket:}") String bucket,
            @Value("${app.r2.enabled:false}") boolean enabled,
            @Value("${app.r2.upload-enabled:true}") boolean uploadsEnabled) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.images = images;
        this.r2 = enabled ? clients.getIfAvailable() : null;
        this.bucket = bucket;
        this.uploadsEnabled = uploadsEnabled;
        if (enabled && (r2 == null || bucket.isBlank())) throw new IllegalStateException("Avatar R2 configuration is incomplete");
    }

    @Override
    public String store(MultipartFile file) {
        byte[] png = images.toAvatarPng(file);
        String filename = UUID.randomUUID() + ".png";
        if (r2 != null) {
            if (!uploadsEnabled) throw invalid("Chức năng tải ảnh đang tạm dừng. Vui lòng thử lại sau.");
            try {
                r2.putObject(PutObjectRequest.builder().bucket(bucket).key("avatars/" + filename)
                    .contentType("image/png").build(), RequestBody.fromBytes(png));
            } catch (SdkException error) {
                log.warn("Avatar R2 upload failed ({})", error instanceof S3Exception s3 ? s3.statusCode() : "connection");
                throw invalid("Không thể lưu ảnh đại diện. Vui lòng thử lại.");
            }
        } else {
            try {
                Files.createDirectories(root);
                Files.write(path(filename), png, StandardOpenOption.CREATE_NEW);
            } catch (IOException error) {
                delete(PREFIX + filename);
                log.warn("Local avatar write failed ({})", error.getClass().getSimpleName());
                throw invalid("Không thể lưu ảnh đại diện. Vui lòng thử lại.");
            }
        }
        return PREFIX + filename;
    }

    @Override
    public byte[] read(String filename) {
        path(filename);
        if (r2 != null) {
            try (var stream = r2.getObject(GetObjectRequest.builder().bucket(bucket).key("avatars/" + filename).build())) {
                Long length = stream.response().contentLength();
                if (!"image/png".equals(stream.response().contentType()) || (length != null && (length <= 0 || length > MAX_BYTES))) {
                    stream.abort();
                    throw invalid("Không thể đọc ảnh đại diện.");
                }
                byte[] bytes;
                try {
                    bytes = stream.readNBytes(MAX_BYTES + 1);
                } catch (IOException error) {
                    stream.abort();
                    throw error;
                }
                if (bytes.length == 0 || bytes.length > MAX_BYTES || (length != null && bytes.length != length)) {
                    stream.abort();
                    throw invalid("Không thể đọc ảnh đại diện.");
                }
                return bytes;
            } catch (S3Exception error) {
                if (error.statusCode() != 404) {
                    log.warn("Avatar R2 read failed ({})", error.statusCode());
                    throw invalid("Không thể đọc ảnh đại diện. Vui lòng thử lại.");
                }
                // Existing avatars may still live on the old local volume during rollout.
            } catch (SdkException | IOException error) {
                log.warn("Avatar R2 read failed ({})", error.getClass().getSimpleName());
                throw invalid("Không thể đọc ảnh đại diện. Vui lòng thử lại.");
            }
        }
        try {
            return Files.readAllBytes(path(filename));
        } catch (IOException error) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    @Override
    public void delete(String url) {
        if (url == null || !url.startsWith(PREFIX)) return;
        String filename = url.substring(PREFIX.length());
        path(filename);
        if (r2 != null) {
            try {
                r2.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key("avatars/" + filename).build());
            } catch (SdkException error) {
                log.warn("Avatar R2 cleanup failed ({})", error.getClass().getSimpleName());
            }
        }
        try {
            Files.deleteIfExists(path(filename));
        } catch (IOException error) {
            log.warn("Local avatar cleanup failed ({})", error.getClass().getSimpleName());
        }
    }

    private Path path(String filename) {
        if (filename == null || !filename.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png")) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return root.resolve(filename);
    }

    private AppException invalid(String message) {
        return new AppException(ErrorCode.INVALID_INPUT_DATA, message);
    }
}
