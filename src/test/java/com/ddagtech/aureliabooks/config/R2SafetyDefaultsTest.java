package com.ddagtech.aureliabooks.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;
import static org.assertj.core.api.Assertions.*;

/** Audit packaged defaults without creating an application context or datasource. */
class R2SafetyDefaultsTest {
    @Test void packagedDefaultsNeverAskHibernateOrDriverToCreateSchema() throws Exception {
        var source = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).getFirst();
        assertThat(source.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(source.getProperty("spring.datasource.url").toString()).doesNotContain("createDatabaseIfNotExist");
    }
    @Test void servletLimitsBoundFileRequestAndDiskThreshold() throws Exception {
        var source = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).getFirst();
        assertThat(DataSize.parse(source.getProperty("spring.servlet.multipart.max-file-size").toString()).toBytes())
                .isEqualTo(5L * 1024 * 1024);
        assertThat(DataSize.parse(source.getProperty("spring.servlet.multipart.max-request-size").toString()).toBytes())
                .isEqualTo(6L * 1024 * 1024);
        assertThat(DataSize.parse(source.getProperty("spring.servlet.multipart.file-size-threshold").toString()).toBytes()).isZero();
    }
}
