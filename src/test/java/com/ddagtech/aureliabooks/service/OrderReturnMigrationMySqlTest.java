package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.repository.AddressOrderGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL only; creates/drops a random local QA database, never the application database. */
@EnabledIfSystemProperty(named = "orderreturn.mysql", matches = "true")
class OrderReturnMigrationMySqlTest {
    private final String database = "uc14_qa_" + UUID.randomUUID().toString().replace("-", "");
    private DriverManagerDataSource admin;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private boolean created;
    private int sequence;

    @BeforeEach void isolatedDatabase() {
        String url = System.getProperty("orderreturn.mysql.url", "jdbc:mysql://127.0.0.1:3306/?serverTimezone=UTC");
        assertThat(url).matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/(\\?.*)?");
        String user = System.getProperty("orderreturn.mysql.user", "root");
        String password = System.getProperty("orderreturn.mysql.password", "");
        admin = new DriverManagerDataSource(url, user, password);
        verifyQaName();
        new JdbcTemplate(admin).execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        created = true;
        int start = url.indexOf('/', "jdbc:mysql://".length());
        source = new DriverManagerDataSource(url.substring(0, start + 1) + database + url.substring(start + 1), user, password);
        jdbc = new JdbcTemplate(source);
    }

    @AfterEach void dropOnlyThisQaDatabase() {
        if (!created) return;
        verifyQaName();
        new JdbcTemplate(admin).execute("DROP DATABASE `" + database + "`");
    }

    private void verifyQaName() {
        assertThat(database).matches("uc14_qa_[a-f0-9]{32}");
    }

    private void script(String sql) {
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
    }

    private void schema(String path, boolean legacy) throws Exception {
        String sql = Files.readString(Path.of(path));
        // Do not execute the root schema's DROP/CREATE DATABASE/USE against any real database.
        sql = sql.substring(sql.indexOf("CREATE TABLE roles"));
        if (legacy) sql = sql.replace("'DELIVERED','CANCELLED','RETURNED')", "'DELIVERED','CANCELLED')");
        script(sql);
        jdbc.update("INSERT INTO roles(id,role_name) VALUES(1,'ROLE_CUSTOMER'),(2,'ROLE_SALE_STAFF')");
        jdbc.update("INSERT INTO users(id,role_id,full_name,password_hash) VALUES(1,1,'Buyer','hash'),(2,2,'Staff','hash')");
        jdbc.update("INSERT INTO shipping_addresses(id,user_id,recipient_name,phone,economic_region,province,ward,detailed_address) "
                + "VALUES(1,1,'Buyer','0912345678','NORTHERN','Ha Noi','Ward','Address')");
        jdbc.update("INSERT INTO categories(id,name) VALUES(1,'Books')");
        jdbc.update("INSERT INTO products(id,barcode,title,category_id,product_type,price,original_cost,stock_quantity) "
                + "VALUES(1,'QA','Book',1,'BOOK',100000,50000,7)");
    }

    private long order(String status, String payment, String restorationReason) {
        long id = ++sequence;
        jdbc.update("INSERT INTO orders(id,order_code,user_id,shipping_address_id,shipping_recipient_name,shipping_phone,"
                + "shipping_full_address,shipping_fee,final_total_amount,payment_method,payment_status,order_status,"
                + "stock_deducted_at,stock_restored_at,stock_restore_reason,stock_restored_by_user_id,delivered_at) "
                + "VALUES(?,?,1,1,'Snapshot buyer','0912345678','Historical address',0,100000,'COD',?,?,"
                + "'2026-10-20 12:00:00',?,?,?,?)", id, "qa-" + id, payment, status,
                restorationReason == null ? null : "2026-10-25 12:00:00", restorationReason,
                restorationReason == null ? null : 2L, status.equals("DELIVERED") ? "2026-10-24 12:00:00" : null);
        jdbc.update("INSERT INTO order_items(order_id,product_id,quantity,product_name_snapshot,unit_price_snapshot) "
                + "VALUES(?,1,2,'Historical book',50000)", id);
        return id;
    }

    private void migrate() throws Exception {
        String sql = Files.readString(Path.of("docker/migrations/20261030_order_returned_status.sql"));
        script(sql.replaceAll("(?im)^USE\\s+\\w+;\\s*", ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema.sql", "docker/initdb/01_schema.sql"})
    void eitherFreshSchemaAcceptsReturnedWithoutHibernateRepair(String path) throws Exception {
        schema(path, false);
        long id = order("RETURNED", "PAID", "RETURNED");
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id=?", String.class, id)).isEqualTo("RETURNED");
    }

    @Test void upgradeBackfillsOnlyPostedReturnsAndIsSafeToRepeat() throws Exception {
        schema("schema.sql", true);
        long shipping = order("SHIPPING", "PAID", "RETURNED");
        long delivered = order("DELIVERED", "PAID", "RETURNED");
        long refunded = order("DELIVERED", "REFUNDED", "RETURNED");
        long unpaid = order("DELIVERED", "UNPAID", "RETURNED");
        long noReturn = order("DELIVERED", "PAID", null);
        long inTransit = order("SHIPPING", "PAID", null);
        long cancelled = order("CANCELLED", "PAID", "CANCELLED");
        var before = jdbc.queryForList("SELECT id,payment_status,stock_deducted_at,stock_restored_at,stock_restore_reason,"
                + "stock_restored_by_user_id,delivered_at,shipping_full_address,final_total_amount FROM orders ORDER BY id");
        var items = jdbc.queryForList("SELECT * FROM order_items ORDER BY id");
        migrate();
        for (long id : List.of(shipping, delivered, refunded, unpaid)) {
            assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id=?", String.class, id)).isEqualTo("RETURNED");
        }
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id=?", String.class, noReturn)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id=?", String.class, inTransit)).isEqualTo("SHIPPING");
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id=?", String.class, cancelled)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("SELECT id FROM orders WHERE order_status='RETURNED' AND payment_status='PAID' ORDER BY id"))
                .extracting(row -> ((Number) row.get("id")).longValue()).containsExactly(shipping, delivered);
        var after = jdbc.queryForList("SELECT * FROM orders ORDER BY id");
        migrate();
        assertThat(jdbc.queryForList("SELECT * FROM orders ORDER BY id")).isEqualTo(after);
        assertThat(jdbc.queryForList("SELECT id,payment_status,stock_deducted_at,stock_restored_at,stock_restore_reason,"
                + "stock_restored_by_user_id,delivered_at,shipping_full_address,final_total_amount FROM orders ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM order_items ORDER BY id")).isEqualTo(items);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=1", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class)).isZero();
    }

    @Test void returnedOrdersDoNotLockAddressesButAnUnfinishedOrderStillDoes() throws Exception {
        schema("schema.sql", false);
        var guard = new AddressOrderGuard(jdbc);
        long returned = order("RETURNED", "PAID", "RETURNED");
        assertThat(guard.hasUnfinishedOrder(1L, 1L)).isFalse();
        jdbc.update("UPDATE orders SET payment_status='REFUNDED' WHERE id=?", returned);
        assertThat(guard.hasUnfinishedOrder(1L, 1L)).isFalse();
        long active = order("SHIPPING", "UNPAID", null);
        assertThat(guard.hasUnfinishedOrder(1L, 1L)).isTrue();
        jdbc.update("UPDATE orders SET shipping_address_id=NULL WHERE id=?", active);
        assertThat(guard.hasUnfinishedOrder(1L, 1L)).isTrue();
        jdbc.update("UPDATE orders SET order_status='DELIVERED' WHERE id=?", active);
        assertThat(guard.hasUnfinishedOrder(1L, 1L)).isFalse();
        assertThat(jdbc.queryForObject("SELECT shipping_full_address FROM orders WHERE id=?", String.class, returned))
                .isEqualTo("Historical address");
    }
}
