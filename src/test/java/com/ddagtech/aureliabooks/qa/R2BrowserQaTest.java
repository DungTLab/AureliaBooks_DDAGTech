package com.ddagtech.aureliabooks.qa;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.ProductImageController;
import com.ddagtech.aureliabooks.controller.admin.*;
import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.*;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.security.*;
import com.ddagtech.aureliabooks.service.*;
import com.ddagtech.aureliabooks.service.impl.ProductImageProcessor;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in local browser harness. Never loads the production component scan or a datasource.
 * Storage and catalog persistence are test doubles: this is not live-R2 or database E2E.
 */
@EnabledIfSystemProperty(named = "r2.browser.qa", matches = "true")
@SpringBootTest(classes = R2BrowserQaTest.Harness.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "app.r2.enabled=true", "app.r2.upload-enabled=true",
        "app.r2.upload-requests-per-minute=120", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.main.banner-mode=off"})
class R2BrowserQaTest {
    @LocalServerPort int port;
    @Autowired ApplicationContext context;

    @Test void serveLoopbackUntilStopFile() throws Exception {
        assertThat(context.getBeansOfType(com.ddagtech.aureliabooks.AureliaBooksApplication.class)).isEmpty();
        assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(software.amazon.awssdk.services.s3.S3Client.class)).isEmpty();
        Path stop = Path.of("target/r2-browser-qa.stop");
        Files.deleteIfExists(stop);
        Files.writeString(Path.of("target/r2-browser-qa.json"), "{\"url\":\"http://127.0.0.1:" + port + "\"}");
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(15).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(250);
        Files.deleteIfExists(Path.of("target/r2-browser-qa.json"));
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.test.context.TestComponent
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class})
    @Import({SecurityConfig.class, AdminProductController.class, ManagerProductImageController.class,
            ProductImageController.class, GlobalExceptionHandler.class, CustomAuthenticationEntryPoint.class,
            CustomAccessDeniedHandler.class, Pages.class})
    static class Harness {
        private final Map<String, byte[]> images = new ConcurrentHashMap<>();
        private final AtomicReference<BookForm> book = new AtomicReference<>();
        private final AtomicReference<StationeryForm> stationery = new AtomicReference<>();
        private final AtomicBoolean failUploads = new AtomicBoolean();
        @Bean UserDetailsService qaUsers() {
            return new InMemoryUserDetailsManager(org.springframework.security.core.userdetails.User.withUsername("qa-manager@example.test")
                    .password(new BCryptPasswordEncoder(4).encode("QaOnly-NotReal-391"))
                    .roles("MANAGER").build());
        }
        @Bean ProductImageStorage qaStorage() {
            ProductImageProcessor processor = new ProductImageProcessor();
            return new ProductImageStorage() {
                public String store(MultipartFile file) {
                    if (failUploads.get()) throw new IllegalStateException("QA simulated outage");
                    String id = UUID.randomUUID().toString();
                    images.put(id, processor.toPng(file));
                    return "products/" + id + ".png";
                }
                public byte[] read(String id) {
                    byte[] bytes = images.get(id);
                    if (bytes == null) throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
                    return bytes.clone();
                }
            };
        }
        @Bean ProductRepository qaProducts() {
            ProductRepository repository = mock(ProductRepository.class);
            when(repository.countVisibleProductsByMainImageUrl(anyString())).thenAnswer(call -> {
                String url = call.getArgument(0);
                long count = book.get() != null && url.equals(book.get().mainImageUrl()) ? 1 : 0;
                return count + (stationery.get() != null && url.equals(stationery.get().mainImageUrl()) ? 1 : 0);
            });
            return repository;
        }
        @Bean AdminProductService qaAdminProducts() {
            AdminProductService service = mock(AdminProductService.class);
            when(service.createBook(any())).thenAnswer(call -> {book.set(withId(call.getArgument(0))); return 1L;});
            when(service.createStationery(any())).thenAnswer(call -> {stationery.set(withId(call.getArgument(0))); return 1L;});
            when(service.getBookForEdit(1L)).thenAnswer(call -> book.get());
            when(service.getStationeryForEdit(1L)).thenAnswer(call -> stationery.get());
            doAnswer(call -> {book.set(withId(call.getArgument(1))); return null;}).when(service).updateBook(eq(1L), any());
            doAnswer(call -> {stationery.set(withId(call.getArgument(1))); return null;}).when(service).updateStationery(eq(1L), any());
            when(service.listProducts(any(), any(), any())).thenReturn(org.springframework.data.domain.Page.empty());
            return service;
        }
        @SuppressWarnings("unchecked")
        private <T extends Record> T withId(T form) throws Exception {
            var components = form.getClass().getRecordComponents();
            Class<?>[] types = Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
            Object[] values = new Object[components.length];
            for (int i = 0; i < values.length; i++) values[i] = components[i].getAccessor().invoke(form);
            values[0] = 1L;
            return (T) form.getClass().getDeclaredConstructor(types).newInstance(values);
        }
        @Bean MasterDataService qaMasterData() {
            MasterDataService service = mock(MasterDataService.class);
            when(service.getAllActiveCategories()).thenReturn(List.of(Category.builder().id(1L).name("QA category").build()));
            when(service.getAllPublishers()).thenReturn(List.of(Publisher.builder().id(1L).name("QA publisher").build()));
            when(service.getAllAuthors()).thenReturn(List.of(Author.builder().id(1L).name("QA author").build()));
            when(service.getAllBrands()).thenReturn(List.of(Brand.builder().id(1L).name("QA brand").build()));
            return service;
        }
        @Bean AtomicBoolean qaUploadFault() { return failUploads; }
    }

    @Controller
    static class Pages {
        @Autowired AtomicBoolean qaUploadFault;
        @GetMapping("/internal/login") String login() {return "internal/login";}
        @GetMapping("/dashboard") String dashboard() {return "redirect:/manager/books/new";}
        @PostMapping("/manager/qa-upload-fault") @ResponseBody
        Map<String, Boolean> fault(@RequestParam boolean fail) {
            qaUploadFault.set(fail);
            return Map.of("fail", fail);
        }
    }
}
