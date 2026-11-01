package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.service.ProductImageStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;

@Service
@ConditionalOnProperty(
        prefix = "app.r2",
        name = "enabled",
        havingValue = "true"
)
public class R2ProductImageStorage implements ProductImageStorage {

    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    private static final Pattern IMAGE_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    );

    private final S3Client s3Client;
    private final ProductImageProcessor imageProcessor;
    private final String bucket;

    public R2ProductImageStorage(
            S3Client s3Client,
            ProductImageProcessor imageProcessor,
            @Value("${app.r2.bucket:}") String bucket) {

        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "R2 bucket is not configured"
            );
        }

        this.s3Client = s3Client;
        this.imageProcessor = imageProcessor;
        this.bucket = bucket;
    }

    @Override
    public String store(MultipartFile file) {

        byte[] png = imageProcessor.toPng(file);

        String objectKey =
                "products/" + UUID.randomUUID() + ".png";

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType("image/png")
                .build();

        try {
            s3Client.putObject(
                    request,
                    RequestBody.fromBytes(png)
            );

            return objectKey;

        } catch (SdkException e) {
            throw new IllegalStateException(
                    "Cannot upload product image to R2",
                    e
            );
        }
    }

    @Override
    public byte[] read(String imageId) {
        if (imageId == null || !IMAGE_ID.matcher(imageId).matches()) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        String objectKey = "products/" + imageId + ".png";

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        try (var stream = s3Client.getObject(request)) {
            Long length = stream.response().contentLength();

            if (!"image/png".equals(stream.response().contentType())
                    || (length != null
                    && (length <= 0 || length > MAX_IMAGE_BYTES))) {
                stream.abort();
                throw new IllegalStateException(
                        "Invalid stored product image metadata"
                );
            }

            byte[] bytes;
            try {
                bytes = stream.readNBytes(MAX_IMAGE_BYTES + 1);
            } catch (IOException e) {
                stream.abort();
                throw e;
            }

            if (bytes.length == 0
                    || bytes.length > MAX_IMAGE_BYTES
                    || (length != null && bytes.length != length)) {
                stream.abort();
                throw new IllegalStateException(
                        "Invalid stored product image size"
                );
            }

            return bytes;

        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
            }

            throw new IllegalStateException(
                    "Cannot read product image from R2", e
            );

        } catch (SdkException | IOException e) {
            throw new IllegalStateException(
                    "Cannot read product image from R2", e
            );
        }
    }

}
