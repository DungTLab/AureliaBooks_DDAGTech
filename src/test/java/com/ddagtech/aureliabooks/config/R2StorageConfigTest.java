package com.ddagtech.aureliabooks.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

class R2StorageConfigTest {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withUserConfiguration(R2StorageConfig.class);

    @Test
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).doesNotHaveBean(S3Client.class);
        });
    }

    @Test
    void disabledExplicitly() {
        runner.withPropertyValues("app.r2.enabled=false")
            .run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context).doesNotHaveBean(S3Client.class);
            });
    }

    @Test
    void enabledWithoutCredentialsFails() {
        runner.withPropertyValues("app.r2.enabled=true")
            .run(context -> {
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            });
    }

    @Test
    void invalidEndpointFails() {
        runner.withPropertyValues(
            "app.r2.enabled=true",
            "app.r2.endpoint=https://example.com",
            "app.r2.bucket=test-bucket",
            "app.r2.access-key-id=dummy",
            "app.r2.secret-access-key=dummy"
        ).run(context -> {
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void validConfigurationCreatesClient() {
        runner.withPropertyValues(
            "app.r2.enabled=true",
            "app.r2.endpoint=https://1234567890abcdef.r2.cloudflarestorage.com",
            "app.r2.bucket=test-bucket",
            "app.r2.access-key-id=dummy",
            "app.r2.secret-access-key=dummy"
        ).run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(S3Client.class);
            var overrides = context.getBean(S3Client.class).serviceClientConfiguration().overrideConfiguration();
            assertThat(overrides.apiCallTimeout()).contains(java.time.Duration.ofSeconds(30));
            assertThat(overrides.apiCallAttemptTimeout()).contains(java.time.Duration.ofSeconds(10));
        });
    }
}
