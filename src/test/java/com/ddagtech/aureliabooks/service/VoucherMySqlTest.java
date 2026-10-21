package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.VoucherForm;
import com.ddagtech.aureliabooks.entity.Voucher;
import com.ddagtech.aureliabooks.exception.AppException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in only. Uses a disposable voucher_qa_ database, never the application database. */
@SpringBootTest(properties = {
    "spring.datasource.url=${VOUCHER_QA_URL:jdbc:mysql://127.0.0.1:33327/voucher_qa_20261017?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Ho_Chi_Minh}",
    "spring.datasource.username=root",
    "spring.datasource.password=${VOUCHER_QA_PASSWORD:voucher_qa_local}"
})
@EnabledIfSystemProperty(named = "voucher.mysql", matches = "true")
class VoucherMySqlTest {
    @Autowired VoucherService vouchers;
    @Autowired StockMutationService stock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock promotionClock;
    @Autowired com.ddagtech.aureliabooks.repository.ProductRepository products;
    private TransactionTemplate tx;
    private long manager;
    private long customer;
    private long other;
    private long product;
    private String code;

    @BeforeEach
    void setup() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("voucher_qa_");
        tx = new TransactionTemplate(transactions);
        manager = user(2);
        customer = user(4);
        other = user(4);
        long category = insert("categories", "INSERT INTO categories(name) VALUES(?)", UUID.randomUUID().toString());
        product = insert("products", "INSERT INTO products(barcode,title,category_id,product_type,price,original_cost,stock_quantity) VALUES(?,'Voucher QA',?,'BOOK',500000,100000,100)",
                UUID.randomUUID().toString(), category);
        code = "QA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    @Test
    void previewDoesNotReserveAndOrderUsesServerSnapshots() {
        long voucher = campaign(3);
        assertThat(vouchers.preview(customer, code, new BigDecimal("500000"), new BigDecimal("30000")).discountAmount())
                .isEqualByComparingTo("50000");
        assertThat(remaining(voucher)).isEqualTo(3);
        assertThat(redemptions(voucher)).isZero();
        long order = place(customer, UUID.randomUUID().toString().replace("-", ""));
        assertThat(remaining(voucher)).isEqualTo(2);
        assertThat(redemptions(voucher)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT final_total_amount FROM orders WHERE id=?", BigDecimal.class, order))
                .isEqualByComparingTo("480000");
        assertThat(jdbc.queryForObject("SELECT discount_applied FROM order_vouchers WHERE order_id=?", BigDecimal.class, order))
                .isEqualByComparingTo("50000");
        assertThat(balance()).isEqualTo(99);
        tx.executeWithoutResult(status -> vouchers.redeem(customer, order, code, "ip"));
        assertThat(remaining(voucher)).isEqualTo(2);
        assertThat(redemptions(voucher)).isEqualTo(1);
        assertThat(vouchers.list(manager, code, null, PageRequest.of(0,20)).getContent().getFirst().redemptions()).isEqualTo(1);
        assertThatThrownBy(() -> vouchers.preview(customer, code, new BigDecimal("500000"), BigDecimal.ZERO))
                .isInstanceOf(AppException.class);
    }

    @Test
    void oneRemainingUseAllowsBothPreviewsButOnlyOneAtomicPlacement() throws Exception {
        long voucher = campaign(1);
        assertThat(vouchers.preview(customer, code, new BigDecimal("500000"), BigDecimal.ZERO)).isNotNull();
        assertThat(vouchers.preview(other, code, new BigDecimal("500000"), BigDecimal.ZERO)).isNotNull();
        race(customer, other);
        assertThat(remaining(voucher)).isZero();
        assertThat(redemptions(voucher)).isEqualTo(1);
        assertThat(balance()).isEqualTo(99);
    }

    @Test
    void sameCustomerCannotConsumeTwiceEvenWhenGlobalQuotaHasTwoUses() throws Exception {
        long voucher = campaign(2);
        race(customer, customer);
        assertThat(remaining(voucher)).isEqualTo(1);
        assertThat(redemptions(voucher)).isEqualTo(1);
        assertThat(balance()).isEqualTo(99);
    }

    @Test
    void differentProductsStillContendOnOneGlobalVoucherQuota() throws Exception {
        long voucher = campaign(1);
        long category = jdbc.queryForObject("SELECT category_id FROM products WHERE id=?", Long.class, product);
        long secondProduct = insert("products", "INSERT INTO products(barcode,title,category_id,product_type,price,original_cost,stock_quantity) VALUES(?,'Other QA',?,'BOOK',500000,100000,100)", UUID.randomUUID().toString(), category);
        race(customer, other, product, secondProduct);
        assertThat(remaining(voucher)).isZero();
        assertThat(redemptions(voucher)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(stock_quantity) FROM products WHERE id IN (?,?)", Integer.class, product, secondProduct)).isEqualTo(199);
    }

    @Test
    void rollbackAfterRedemptionAndStockPostingRestoresEverything() {
        long voucher = campaign(2);
        String orderCode = UUID.randomUUID().toString().replace("-", "");
        assertThatThrownBy(() -> tx.execute(status -> {
            long order = order(customer, orderCode);
            vouchers.redeem(customer, order, code, "ip");
            stock.deductOrder(order);
            throw new IllegalStateException("Simulated placement failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(remaining(voucher)).isEqualTo(2);
        assertThat(redemptions(voucher)).isZero();
        assertThat(balance()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE order_code=?", Integer.class, orderCode)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_table='vouchers' AND target_id=? AND action='VOUCHER_REDEEMED'", Integer.class, voucher)).isZero();
    }

    @Test
    void cancellationReleasesUsageOnceAndSameCustomerCanReuse() {
        long voucher = campaign(1);
        long order = place(customer, UUID.randomUUID().toString().replace("-", ""));
        tx.executeWithoutResult(status -> {
            stock.restoreCancelledOrder(order, customer);
            vouchers.releaseCancelledOrder(order, customer, "ip");
        });
        tx.executeWithoutResult(status -> vouchers.releaseCancelledOrder(order, customer, "ip"));
        assertThat(remaining(voucher)).isEqualTo(1);
        assertThat(redemptions(voucher)).isZero();
        assertThat(balance()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT final_total_amount FROM orders WHERE id=?", BigDecimal.class, order))
                .isEqualByComparingTo("480000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_table='vouchers' AND target_id=? AND action='VOUCHER_RELEASED'", Integer.class, voucher)).isEqualTo(1);
        place(customer, UUID.randomUUID().toString().replace("-", ""));
        assertThat(remaining(voucher)).isZero();
        assertThat(redemptions(voucher)).isEqualTo(1);
    }

    @Test
    void refundOrReturnWithoutCancellationDoesNotReleaseVoucher() {
        long voucher = campaign(1);
        long order = place(customer, UUID.randomUUID().toString().replace("-", ""));
        jdbc.update("UPDATE orders SET order_status='DELIVERED',payment_status='REFUNDED' WHERE id=?", order);
        tx.executeWithoutResult(status -> stock.restoreReturnedOrder(order, manager));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> vouchers.releaseCancelledOrder(order, manager, "ip")))
                .isInstanceOf(AppException.class);
        assertThat(remaining(voucher)).isZero();
        assertThat(redemptions(voucher)).isEqualTo(1);
    }

    @Test
    void ownershipOneVoucherPerOrderAndMandatoryTransactionAreEnforced() {
        campaign(3);
        long order = place(customer, UUID.randomUUID().toString().replace("-", ""));
        assertThatThrownBy(() -> vouchers.redeem(customer, order, code, "ip"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> vouchers.redeem(other, order, code, "ip")))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> vouchers.redeem(customer, order, "OTHER", "ip")))
                .isInstanceOf(AppException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_vouchers WHERE order_id=?", Integer.class, order)).isEqualTo(1);
    }

    @Test
    void managerCanCreateToggleAndSearchButCustomerCannotManage() {
        long voucher = campaign(2);
        assertThatThrownBy(() -> campaign(2)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> vouchers.setActive(customer, voucher, false, "ip")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> vouchers.create(customer, form(2), "ip")).isInstanceOf(AppException.class);
        vouchers.setActive(manager, voucher, false, "ip");
        assertThatThrownBy(() -> vouchers.preview(customer, code, new BigDecimal("500000"), BigDecimal.ZERO))
                .isInstanceOf(AppException.class);
        vouchers.setActive(manager, voucher, true, "ip");
        assertThat(vouchers.list(manager, code, true, PageRequest.of(0,20)).getTotalElements()).isEqualTo(1);
        assertThat(vouchers.list(manager, "%_", null, PageRequest.of(0,20)).getTotalElements()).isZero();
    }

    private void race(long firstCustomer, long secondCustomer) throws Exception {
        race(firstCustomer, secondCustomer, product, product);
    }

    private void race(long firstCustomer, long secondCustomer, long firstProduct, long secondProduct) throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        String a = UUID.randomUUID().toString().replace("-", "");
        String b = UUID.randomUUID().toString().replace("-", "");
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> first = attempt(firstCustomer, a, firstProduct, ready, go);
            Callable<Boolean> second = attempt(secondCustomer, b, secondProduct, ready, go);
            var one = pool.submit(first);
            var two = pool.submit(second);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE order_code IN (?,?)", Integer.class, a, b)).isEqualTo(1);
    }

    private Callable<Boolean> attempt(long owner, String orderCode, long selectedProduct, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            go.await();
            try {
                place(owner, orderCode, selectedProduct);
                return true;
            } catch (AppException ex) {
                return false;
            }
        };
    }

    private long place(long owner, String orderCode) {
        return place(owner, orderCode, product);
    }

    private long place(long owner, String orderCode, long selectedProduct) {
        return tx.execute(status -> {
            products.findAllByIdInWithLock(List.of(selectedProduct));
            long id = order(owner, orderCode, selectedProduct);
            vouchers.redeem(owner, id, code, "ip");
            stock.deductOrder(id);
            return id;
        });
    }

    private long order(long owner, String orderCode) {
        return order(owner, orderCode, product);
    }

    private long order(long owner, String orderCode, long selectedProduct) {
        long id = insert("orders", "INSERT INTO orders(order_code,user_id,shipping_recipient_name,shipping_phone,shipping_full_address,shipping_fee,final_total_amount,payment_method) VALUES(?,?,'QA','0912345678','QA address',30000,530000,'COD')", orderCode, owner);
        jdbc.update("INSERT INTO order_items(order_id,product_id,quantity,product_name_snapshot,unit_price_snapshot) VALUES(?,?,1,'QA',500000)", id, selectedProduct);
        return id;
    }

    private VoucherForm form(int uses) {
        LocalDateTime now = LocalDateTime.now(promotionClock);
        return new VoucherForm(code, "Giảm 10%", Voucher.Scope.PRODUCT, Voucher.Type.PERCENTAGE,
                BigDecimal.TEN, null, new BigDecimal("50000"), BigDecimal.ZERO, now.minusDays(1), now.plusDays(1), uses);
    }

    private long campaign(int uses) {
        return vouchers.create(manager, form(uses), "ip");
    }

    private long user(int role) {
        String email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users(role_id,email,full_name,password_hash) VALUES(?,?,'Voucher QA','qa-hash')", role, email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private long insert(String table, String sql, Object... args) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey(), "Missing generated key for " + table).longValue();
    }

    private int remaining(long voucher) {
        return jdbc.queryForObject("SELECT usage_limit FROM vouchers WHERE id=?", Integer.class, voucher);
    }

    private long redemptions(long voucher) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_vouchers WHERE voucher_id=?", Long.class, voucher);
    }

    private int balance() {
        return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, product);
    }
}
