package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.service.ProductImageStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Isolated MVC context: no application scan, datasource or real storage configuration. */
class ProductImageControllerContextTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class))
            .withUserConfiguration(ProductImageController.class,
                    com.ddagtech.aureliabooks.controller.admin.ManagerProductImageController.class);

    @Test
    void disabledByDefaultWithoutStorageDependencies() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ProductImageController.class);
            assertThat(context).doesNotHaveBean(com.ddagtech.aureliabooks.controller.admin.ManagerProductImageController.class);
            assertThat(context).doesNotHaveBean(ProductRepository.class);
            assertThat(context).doesNotHaveBean(ProductImageStorage.class);
            assertThat(context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().values())
                    .noneMatch(handler -> handler.getBeanType().equals(ProductImageController.class));
        });
    }

    @Test
    void explicitlyDisabledWithoutStorageDependencies() {
        runner.withPropertyValues("app.r2.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ProductImageController.class);
            assertThat(context).doesNotHaveBean(com.ddagtech.aureliabooks.controller.admin.ManagerProductImageController.class);
            assertThat(context).doesNotHaveBean(ProductRepository.class);
            assertThat(context).doesNotHaveBean(ProductImageStorage.class);
            assertThat(context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().values())
                    .noneMatch(handler -> handler.getBeanType().equals(ProductImageController.class));
        });
    }

    @Test
    void enabledRegistersControllerAndImageRoute() {
        ProductRepository products = mock(ProductRepository.class);
        ProductImageStorage storage = mock(ProductImageStorage.class);
        runner.withPropertyValues("app.r2.enabled=true")
                .withBean(ProductRepository.class, () -> products)
                .withBean(ProductImageStorage.class, () -> storage)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProductImageController.class);
                    assertThat(context).hasSingleBean(com.ddagtech.aureliabooks.controller.admin.ManagerProductImageController.class);
                    var mappings = context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods();
                    assertThat(mappings.entrySet()).anySatisfy(entry -> {
                        assertThat(entry.getKey().getPatternValues())
                                .containsExactly("/products/images/{imageId}");
                        assertThat(entry.getValue().getBeanType()).isEqualTo(ProductImageController.class);
                    });
                    verifyNoInteractions(products, storage);
                });
    }
}
