package com.ddagtech.aureliabooks.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
public class R2StorageConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
        prefix = "app.r2",
        name = "enabled",
        havingValue = "true"
    )
    public S3Client r2S3Client(
            @Value("${app.r2.endpoint:}") String endpoint,
            @Value("${app.r2.bucket:}") String bucket,
            @Value("${app.r2.access-key-id:}") String accessKeyId,
            @Value("${app.r2.secret-access-key:}") String secretAccessKey) {

        if (endpoint.isBlank()
                || bucket.isBlank()
                || accessKeyId.isBlank()
                || secretAccessKey.isBlank()) {
            throw new IllegalStateException(
                "R2 is enabled but required configuration is missing"
            );
        }

        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid R2 endpoint", e);
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !uri.getHost().endsWith(".r2.cloudflarestorage.com")
                || uri.getRawUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException(
                "R2 endpoint must be an HTTPS Cloudflare R2 endpoint"
            );
        }

        return S3Client.builder()
            .endpointOverride(uri)
            .region(Region.of("auto"))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        accessKeyId,
                        secretAccessKey
                    )
                )
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build()
            )
            .build();
    }
}
