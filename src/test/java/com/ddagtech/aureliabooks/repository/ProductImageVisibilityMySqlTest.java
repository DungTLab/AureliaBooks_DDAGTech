package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in only: use scripts/test-r2-mysql.sh to identify the local QA container port. */
@DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.sql.init.mode=never",
    "app.r2.enabled=false"
}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "r2.mysql", matches = "true")
class ProductImageVisibilityMySqlTest {
    private static final String IMAGE = "/products/images/" + UUID.randomUUID();
    @Autowired ProductRepository products;
    @Autowired EntityManager em;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String port = required("R2_QA_PORT");
        int number = Integer.parseInt(port);
        if (number < 1024 || number > 65535) {
            throw new IllegalStateException("Invalid QA container port");
        }
        String password = required("R2_QA_PASSWORD");
        String url = "jdbc:mysql://127.0.0.1:" + number
                + "/r2_image_qa?allowPublicKeyRetrieval=true&useSSL=false";
        // Verify the selected database before Hibernate is allowed to initialize.
        try (var connection = DriverManager.getConnection(url, "r2_qa", password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
            if (!result.next() || !"r2_image_qa".equals(result.getString(1))
                    || !result.getString(2).startsWith("r2_qa@")) {
                throw new IllegalStateException("Not the R2 QA database/user");
            }
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "r2_qa");
        registry.add("spring.datasource.password", () -> password);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing explicit QA setting: " + name);
        }
        return value;
    }

    @Test void activeProductAndCategoryAreVisible() {
        product(category(true), true, IMAGE);
        assertCount(IMAGE, 1);
    }

    @Test void inactiveProductIsHidden() {
        product(category(true), false, IMAGE);
        assertCount(IMAGE, 0);
    }

    @Test void inactiveCategoryIsHidden() {
        product(category(false), true, IMAGE);
        assertCount(IMAGE, 0);
    }

    @Test void unreferencedImageIsHidden() {
        product(category(true), true, IMAGE);
        assertCount(IMAGE + "-missing", 0);
    }

    @Test void sharedImageCountsBothVisibleProducts() {
        Category category = category(true);
        product(category, true, IMAGE);
        product(category, true, IMAGE);
        product(category, false, IMAGE);
        assertCount(IMAGE, 2);
    }

    private Category category(boolean active) {
        Category category = Category.builder().name("R2 QA").isActive(active).build();
        em.persist(category);
        return category;
    }

    private void product(Category category, boolean active, String image) {
        em.persist(Product.builder().barcode(UUID.randomUUID().toString())
                .title("R2 visibility QA").category(category)
                .productType(Product.ProductType.BOOK)
                .price(BigDecimal.ONE).originalCost(BigDecimal.ZERO)
                .isActive(active).mainImageUrl(image).build());
    }

    private void assertCount(String image, long expected) {
        em.flush();
        em.clear();
        assertThat(products.countVisibleProductsByMainImageUrl(image)).isEqualTo(expected);
    }
}
