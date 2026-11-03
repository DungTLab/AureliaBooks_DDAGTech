package com.ddagtech.aureliabooks.qa;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.controller.*;
import com.ddagtech.aureliabooks.controller.admin.*;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.exception.*;
import com.ddagtech.aureliabooks.mapper.*;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.security.*;
import com.ddagtech.aureliabooks.service.*;
import com.ddagtech.aureliabooks.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static org.assertj.core.api.Assertions.*;

/** Explicit QA composition: real catalog services + JPA/MySQL; no production scan or S3 client.
 * Run only through scripts/start-r2-persistence-qa.sh. Never use application DB credentials.
 */
@EnabledIfSystemProperty(named = "r2.persistence.qa", matches = "true")
@SpringBootTest(classes = R2PersistenceBrowserQaTest.Harness.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.servlet.context-path=/qa", "app.r2.enabled=true",
                "app.r2.upload-enabled=true", "app.r2.upload-requests-per-minute=6", "app.r2.upload-max-concurrent=2",
                "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never",
                "spring.datasource.hikari.maximum-pool-size=3", "spring.main.banner-mode=off"})
class R2PersistenceBrowserQaTest {
    static final State STATE = new State();
    static String url;
    static String password;
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        String rawPort = Objects.requireNonNull(System.getenv("R2_QA_PORT"), "Explicit QA port required");
        int port = Integer.parseInt(rawPort);
        if (port < 1024 || port > 65535) throw new IllegalStateException("Invalid QA port");
        password = Objects.requireNonNull(System.getenv("R2_QA_PASSWORD"), "Explicit QA password required");
        url = "jdbc:mysql://127.0.0.1:" + port
                + "/r2_image_qa?allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=5000&socketTimeout=10000";
        try (var connection = DriverManager.getConnection(url, "r2_qa", password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
            if (!result.next() || !"r2_image_qa".equals(result.getString(1))
                    || !result.getString(2).startsWith("r2_qa@")) throw new IllegalStateException("Not QA database/user");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "r2_qa");
        registry.add("spring.datasource.password", () -> password);
    }

    @Test void serveGuardedPersistenceQa() throws Exception {
        assertThat(context.getBeansOfType(com.ddagtech.aureliabooks.AureliaBooksApplication.class)).isEmpty();
        assertThat(context.getBeansOfType(software.amazon.awssdk.services.s3.S3Client.class)).isEmpty();
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("r2_image_qa");
        long baseline = jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);
        ConfigurableApplicationContext paused = null;
        try {
            tx.executeWithoutResult(status -> {
                STATE.category = insert("categories", STATE.prefix);
                STATE.publisher = insert("publishers", STATE.prefix);
                STATE.author = insert("authors", STATE.prefix);
                STATE.brand = insert("brands", STATE.prefix);
            });
            Map<String,Object> pausedProperties = new HashMap<>();
            pausedProperties.put("server.address", "127.0.0.1");
            pausedProperties.put("server.port", "0");
            pausedProperties.put("server.servlet.context-path", "/qa");
            pausedProperties.put("spring.datasource.url", url);
            pausedProperties.put("spring.datasource.username", "r2_qa");
            pausedProperties.put("spring.datasource.password", password);
            pausedProperties.put("spring.jpa.hibernate.ddl-auto", "validate");
            pausedProperties.put("spring.sql.init.mode", "never");
            pausedProperties.put("spring.datasource.hikari.maximum-pool-size", "3");
            pausedProperties.put("app.r2.enabled", "true");
            pausedProperties.put("app.r2.upload-enabled", "false");
            paused = new SpringApplicationBuilder(Harness.class)
                    .initializers(app -> app.getEnvironment().getPropertySources()
                            .addFirst(new MapPropertySource("isolated-paused-qa", pausedProperties)))
                    .run();
            assertThat(paused.getBeansOfType(ManagerProductImageController.class)).isEmpty();
            assertThat(paused.getBeansOfType(ProductImageController.class)).hasSize(1);
            assertThat(paused.getBeansOfType(software.amazon.awssdk.services.s3.S3Client.class)).isEmpty();
            int pausedPort = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) paused)
                    .getWebServer().getPort();
            Path stop = Path.of("target/r2-persistence-qa.stop");
            Files.deleteIfExists(stop);
            Files.deleteIfExists(Path.of("target/r2-persistence-http-results.json"));
            Files.writeString(Path.of("target/r2-persistence-qa.json"), json.writeValueAsString(Map.of(
                    "url", "http://127.0.0.1:" + port + "/qa", "pausedUrl", "http://127.0.0.1:" + pausedPort + "/qa",
                    "categoryId", STATE.category, "publisherId", STATE.publisher, "authorId", STATE.author,
                    "brandId", STATE.brand, "runId", STATE.prefix, "quota", 6)));
            long deadline = System.nanoTime() + java.time.Duration.ofMinutes(30).toNanos();
            while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(250);
            assertThat(Files.exists(stop)).as("Explicit stop required; a timeout is not PASS").isTrue();
            var report = json.readTree(Files.readString(Path.of("target/r2-persistence-http-results.json")));
            assertThat(report.path("failures").asInt(-1)).isZero();
            assertThat(report.path("passed").asInt()).isGreaterThanOrEqualTo(28);
        } finally {
            STATE.storage.release();
            if (paused != null) paused.close();
            Map<String,Object> beforeCleanup = new HashMap<>();
            beforeCleanup.put("products", jdbc.queryForList(
                    "SELECT id,barcode,title,main_image_url,stock_quantity FROM products WHERE category_id=?", STATE.category));
            Files.writeString(Path.of("target/r2-persistence-db-evidence.json"), json.writeValueAsString(beforeCleanup));
            tx.executeWithoutResult(status -> {
                Long owned = jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE id=? AND name=?",
                        Long.class, STATE.category, STATE.prefix);
                if (!Long.valueOf(1).equals(owned)) throw new IllegalStateException("QA cleanup ownership guard failed");
                jdbc.update("DELETE ba FROM book_authors ba JOIN products p ON ba.book_id=p.id WHERE p.category_id=?", STATE.category);
                jdbc.update("DELETE b FROM books b JOIN products p ON b.product_id=p.id WHERE p.category_id=?", STATE.category);
                jdbc.update("DELETE s FROM stationeries s JOIN products p ON s.product_id=p.id WHERE p.category_id=?", STATE.category);
                jdbc.update("DELETE FROM products WHERE category_id=?", STATE.category);
                jdbc.update("DELETE FROM categories WHERE id=? AND name=?", STATE.category, STATE.prefix);
                jdbc.update("DELETE FROM publishers WHERE id=? AND name=?", STATE.publisher, STATE.prefix);
                jdbc.update("DELETE FROM authors WHERE id=? AND name=?", STATE.author, STATE.prefix);
                jdbc.update("DELETE FROM brands WHERE id=? AND name=?", STATE.brand, STATE.prefix);
            });
            long after = jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);
            assertThat(after).as("Only this QA run's rows may be removed").isEqualTo(baseline);
            Files.writeString(Path.of("target/r2-persistence-cleanup.json"), json.writeValueAsString(Map.of(
                    "database", "r2_image_qa", "productsBefore", baseline, "productsAfter", after,
                    "remainingRunProducts", jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE category_id=?", Long.class, STATE.category))));
            Files.deleteIfExists(Path.of("target/r2-persistence-qa.json"));
        }
    }
    private long insert(String table, String name) {
        // Only fixed table names passed by seed code, never from an HTTP request.
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO " + table + " (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    static class State {
        final String prefix = "R2E2E_" + UUID.randomUUID();
        long category, publisher, author, brand;
        final AtomicBoolean failCommit = new AtomicBoolean();
        final QaStorage storage = new QaStorage();
    }
    static class QaStorage implements ProductImageStorage {
        final Map<String, byte[]> images = new ConcurrentHashMap<>();
        final AtomicBoolean fail = new AtomicBoolean();
        final AtomicInteger inFlight = new AtomicInteger();
        volatile CountDownLatch held = new CountDownLatch(0);
        void release() { held.countDown(); }
        public String store(MultipartFile file) {
            if (fail.get()) throw new IllegalStateException("QA simulated storage outage");
            byte[] png = new ProductImageProcessor().toPng(file);
            inFlight.incrementAndGet();
            try {
                if (!held.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("QA hold timeout");
                String id = UUID.randomUUID().toString();
                images.put(id, png);
                return "products/" + id + ".png";
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            finally { inFlight.decrementAndGet(); }
        }
        public byte[] read(String id) {
            byte[] png = images.get(id);
            if (png == null) throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
            return png.clone();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.test.context.TestComponent
    @EnableAutoConfiguration
    @EntityScan("com.ddagtech.aureliabooks.entity")
    @EnableJpaRepositories("com.ddagtech.aureliabooks.repository")
    @Import({SecurityConfig.class, AdminProductController.class, ManagerProductImageController.class,
            ProductImageController.class, GlobalExceptionHandler.class, CustomAuthenticationEntryPoint.class,
            CustomAccessDeniedHandler.class, WorkspaceAdvice.class, AdminProductServiceImpl.class,
            MasterDataServiceImpl.class, ProductMapper.class, MasterDataMapper.class,
            ProductImageReferencePolicy.class, Pages.class})
    static class Harness {
        @Bean State qaState() {return STATE;}
        @Bean ProductImageStorage qaStorage() {return STATE.storage;}
        @Bean UserDetailsService qaUsers() {
            var encoder = new BCryptPasswordEncoder(4);
            var users = new ArrayList<org.springframework.security.core.userdetails.UserDetails>();
            for (String name : List.of("manager", "writer", "parallel", "quota", "expiry", "admin", "sale", "customer")) {
                String role = switch (name) {case "admin" -> "ADMIN"; case "sale" -> "SALE_STAFF"; case "customer" -> "CUSTOMER"; default -> "MANAGER";};
                users.add(org.springframework.security.core.userdetails.User.withUsername("qa-"+name+"@example.test")
                        .password(encoder.encode("QaOnly-NotReal-391")).roles(role).build());
            }
            return new InMemoryUserDetailsManager(users);
        }
        @Bean @Primary AdminProductService commitFailureFixture(AdminProductServiceImpl real, TransactionTemplate tx, jakarta.persistence.EntityManager em) {
            return (AdminProductService) Proxy.newProxyInstance(AdminProductService.class.getClassLoader(),
                    new Class<?>[]{AdminProductService.class}, (proxy, method, args) -> {
                        if (method.getName().startsWith("create") || method.getName().startsWith("update")) {
                            return tx.execute(status -> {
                                Object result = invoke(real, method, args);
                                if (STATE.failCommit.getAndSet(false)) {
                                    em.flush(); // Execute SQL first, then prove rollback restores the committed row.
                                    throw new DataIntegrityViolationException("QA injected failure after SQL flush, before commit");
                                }
                                return result;
                            });
                        }
                        return invoke(real, method, args);
                    });
        }
        private static Object invoke(Object target, Method method, Object[] args) {
            try {return method.invoke(target, args);}
            catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(e.getCause());
            } catch (ReflectiveOperationException e) {throw new IllegalStateException(e);}
        }
    }
    @Controller
    static class Pages {
        @Autowired State state;
        @Autowired JdbcTemplate jdbc;
        @Autowired AdminProductService products;
        @GetMapping({"/internal/login", "/products/qa-session"}) String login() {return "internal/login";}
        @GetMapping("/dashboard") String dashboard() {return "redirect:/manager/books/new";}
        @GetMapping("/manager/qa-state") @ResponseBody Map<String,Object> snapshot() {
            return Map.of("database", Objects.requireNonNull(jdbc.queryForObject("SELECT DATABASE()",String.class)),
                    "products",jdbc.queryForList("SELECT id,barcode,title,main_image_url,is_active,stock_quantity FROM products WHERE category_id=? ORDER BY id",state.category),
                    "objectIds",new ArrayList<>(state.storage.images.keySet()),"inFlight",state.storage.inFlight.get());
        }
        @PostMapping("/manager/qa-control") @ResponseBody
        Map<String,Object> control(@RequestParam String action,
                @RequestParam(defaultValue="false") boolean enabled, @RequestParam(required=false) Long productId,
                HttpServletRequest request) {
            switch(action) {
                case "storage-fail" -> state.storage.fail.set(enabled);
                case "commit-fail" -> state.failCommit.set(enabled);
                case "hold" -> {if(enabled) state.storage.held=new CountDownLatch(1); else state.storage.release();}
                case "expire" -> request.getSession().setMaxInactiveInterval(1);
                case "active" -> {
                    var product=products.getProductById(productId);
                    if(!product.getCategory().getId().equals(state.category)) throw new IllegalArgumentException("Not this QA run");
                    if(!Objects.equals(product.getIsActive(),enabled)) products.toggleActive(productId);
                }
                default -> throw new IllegalArgumentException("Unknown QA control");
            }
            return Map.of("ok",true);
        }
    }
}
