package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.ShippingAddress.EconomicRegion;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.CartMapper;
import com.ddagtech.aureliabooks.mapper.OrderMapper;
import com.ddagtech.aureliabooks.mapper.ShippingAddressMapper;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.AddressOrderGuard;
import com.ddagtech.aureliabooks.service.impl.AddressServiceImpl;
import com.ddagtech.aureliabooks.service.impl.AuditLogServiceImpl;
import com.ddagtech.aureliabooks.service.impl.CartServiceImpl;
import com.ddagtech.aureliabooks.service.impl.OrderPlacementServiceImpl;
import com.ddagtech.aureliabooks.service.impl.StockMutationServiceImpl;
import com.ddagtech.aureliabooks.service.impl.OrderStateMachineServiceImpl;
import com.ddagtech.aureliabooks.dto.request.OrderTransitionRequest;
import com.ddagtech.aureliabooks.dto.request.RefundConfirmationRequest;
import com.ddagtech.aureliabooks.dto.request.OrderQueueFilter;
import com.ddagtech.aureliabooks.exception.IllegalStateTransitionException;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ddagtech.aureliabooks.entity.Product;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verify transactions and contention on real InnoDB using only a dedicated database with a random QA name. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=10"
}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderPlacementServiceImpl.class, CartServiceImpl.class, AddressServiceImpl.class,
        CartMapper.class, ShippingAddressMapper.class, OrderMapper.class, AuditLogServiceImpl.class,
        AddressOrderGuard.class, StockMutationServiceImpl.class, OrderStateMachineServiceImpl.class,
        OrderStatusValidator.class,
        com.ddagtech.aureliabooks.service.impl.VoucherServiceImpl.class,
        com.ddagtech.aureliabooks.mapper.VoucherMapper.class,
        com.ddagtech.aureliabooks.config.PromotionConfiguration.class, OrderPlacementMySqlTest.Support.class})
// Avoid a surrounding Spring Test transaction so commit and rollback behavior belongs to the service itself.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfSystemProperty(named = "checkout.mysql", matches = "true")
class OrderPlacementMySqlTest {
    private static final AtomicInteger sequence = new AtomicInteger();
    private static final String database = "uc11_qa_" + UUID.randomUUID().toString().replace("-", "");
    private static String adminUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @Autowired OrderPlacementService service;
    @Autowired OrderStateMachineService stateMachine;
    @Autowired StockMutationService stockMutations;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ProductRepository products;
    @MockitoSpyBean AuditLogService audit;
    private long roleId;
    private long categoryId;

    @TestConfiguration(proxyBeanMethods = false)
    static class Support {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean(destroyMethod = "close") ValidatorFactory validatorFactory() {
            return Validation.buildDefaultValidatorFactory();
        }
        @Bean Validator validator(ValidatorFactory factory) { return factory.getValidator(); }
    }

    /** Isolate QA from the application database; allow only local MySQL and validated QA database names. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        String configured = environment.getRequiredProperty("spring.datasource.url");
        if (!configured.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/.*")) {
            throw new IllegalStateException("Checkout QA requires a local MySQL server.");
        }
        username = environment.getRequiredProperty("spring.datasource.username");
        password = environment.getRequiredProperty("spring.datasource.password");
        int pathStart = configured.indexOf('/', "jdbc:mysql://".length());
        int queryStart = configured.indexOf('?');
        String options = queryStart < 0 ? "" : configured.substring(queryStart);
        adminUrl = configured.substring(0, pathStart + 1) + options;
        verifyDatabaseName();
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
            created = true;
        }
        String testUrl = configured.substring(0, pathStart + 1) + database + options;
        try (Connection connection = DriverManager.getConnection(testUrl, username, password)) {
            String schema = Files.readString(Path.of("docker/initdb/01_schema.sql"));
            // Exclude DROP/CREATE DATABASE/USE from the canonical schema; the connection already selects the QA database.
            schema = schema.substring(schema.indexOf("CREATE TABLE roles"));
            new ResourceDatabasePopulator(new ByteArrayResource(
                    schema.getBytes(StandardCharsets.UTF_8))).populate(connection);
        }
        registry.add("spring.datasource.url", () -> testUrl);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
    }

    /** Drop only the database created by this test run after validating its name again. */
    @AfterAll static void dropOnlyCreatedQaDatabase() throws Exception {
        if (!created) return;
        verifyDatabaseName();
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + database + "`");
        }
    }

    private static void verifyDatabaseName() {
        if (!database.matches("uc11_qa_[a-f0-9]{32}")) {
            throw new IllegalStateException("Invalid disposable checkout database name.");
        }
    }

    @BeforeEach void fixtures() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
        jdbc.update("INSERT INTO roles(role_name) VALUES('ROLE_CUSTOMER') ON DUPLICATE KEY UPDATE role_name=role_name");
        roleId = jdbc.queryForObject("SELECT id FROM roles WHERE role_name='ROLE_CUSTOMER'", Long.class);
        categoryId = insert("INSERT INTO categories(name) VALUES(?)", "QA " + UUID.randomUUID());
    }

    @ParameterizedTest @EnumSource(Order.PaymentMethod.class)
    void commitsMultipleLinesSnapshotsAndClearsCart(Order.PaymentMethod method) {
        List<Long> productIds = List.of(product(10, "100000"), product(10, "50000"));
        Buyer buyer = buyer(productIds, List.of(2, 3));
        Attempt attempt = attempt(buyer, method);
        assertThat(service.placeOrder(buyer.id(), attempt.request(), attempt.confirmation()))
                .isEqualTo(attempt.request().getCheckoutToken());
        assertThat(stock(productIds.getFirst())).isEqualTo(8);
        assertThat(stock(productIds.getLast())).isEqualTo(7);
        assertThat(cartLines(buyer)).isZero();
        long orderId = orderId(attempt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id=?", Integer.class, orderId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id=?", String.class, orderId))
                .isEqualTo(method == Order.PaymentMethod.COD ? "PENDING_CONFIRMATION" : "PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, orderId))
                .isEqualTo("UNPAID");
        assertThat(jdbc.queryForObject("SELECT stock_deducted_at IS NOT NULL FROM orders WHERE id=?", Boolean.class, orderId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT final_total_amount FROM orders WHERE id=?", java.math.BigDecimal.class, orderId))
                .isEqualByComparingTo("400000");
        jdbc.update("UPDATE products SET title='Đã đổi',price=1 WHERE id=?", productIds.getFirst());
        jdbc.update("UPDATE shipping_addresses SET detailed_address='Đã đổi' WHERE id=?", buyer.addressId());
        assertThat(jdbc.queryForObject("SELECT product_name_snapshot FROM order_items WHERE order_id=? AND product_id=?",
                String.class, orderId, productIds.getFirst())).startsWith("Sách QA");
        assertThat(jdbc.queryForObject("SELECT unit_price_snapshot FROM order_items WHERE order_id=? AND product_id=?",
                java.math.BigDecimal.class, orderId, productIds.getFirst())).isEqualByComparingTo("100000");
        assertThat(jdbc.queryForObject("SELECT shipping_full_address FROM orders WHERE id=?", String.class, orderId))
                .startsWith("12 Đội Cấn");
        // Call the stock engine again as confirmation/payment handlers might; the marker must prevent a second deduction.
        stockMutations.deductOrder(orderId);
        assertThat(stock(productIds.getFirst())).isEqualTo(8);
        assertThat(stock(productIds.getLast())).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='ORDER_DEDUCT_STOCK'",
                Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE reference_code=? AND movement_type='ORDER_DEDUCT'",
                Integer.class, attempt.request().getCheckoutToken())).isEqualTo(2);
    }

    @Test void insufficientSecondLinePreservesAllData() {
        List<Long> ids = List.of(product(10, "100000"), product(1, "50000"));
        Buyer buyer = buyer(ids, List.of(2, 2));
        Attempt attempt = attempt(buyer, Order.PaymentMethod.COD);
        assertFailure(ErrorCode.INSUFFICIENT_STOCK, buyer, attempt);
        assertNoOrder(buyer);
        assertThat(stock(ids.getFirst())).isEqualTo(10);
        assertThat(stock(ids.getLast())).isEqualTo(1);
        assertThat(cartLines(buyer)).isEqualTo(2);
    }

    /** Force the second stock update to fail after the first succeeds to verify actual SQL rollback. */
    @Test void failingSecondAtomicUpdateRollsBackOrderItemsAndFirstDeduction() {
        List<Long> ids = List.of(product(10, "100000"), product(10, "50000"));
        Buyer buyer = buyer(ids, List.of(2, 2));
        Attempt attempt = attempt(buyer, Order.PaymentMethod.VNPAY);
        doReturn(0).when(products).deductStockIfAvailable(ids.getLast(), 2);
        assertFailure(ErrorCode.INSUFFICIENT_STOCK, buyer, attempt);
        assertNoOrder(buyer);
        assertThat(stock(ids.getFirst())).isEqualTo(10);
        assertThat(stock(ids.getLast())).isEqualTo(10);
        assertThat(cartLines(buyer)).isEqualTo(2);
        verify(products).deductStockIfAvailable(ids.getFirst(), 2);
    }

    /** A stale entity in the outer transaction must not cause a second deduction after the inner transaction commits. */
    @Test void stockEngineRefreshesPreviouslyLoadedOrderBeforeReplayCheck() {
        long productId = product(5, "100000");
        Buyer buyer = buyer(List.of(productId), List.of(2));
        Attempt attempt = attempt(buyer, Order.PaymentMethod.COD);
        service.placeOrder(buyer.id(), attempt.request(), attempt.confirmation());
        long orderId = orderId(attempt);
        // Prepare an undeducted order to isolate marker handling across two persistence contexts.
        jdbc.update("UPDATE orders SET stock_deducted_at=NULL WHERE id=?", orderId);
        jdbc.update("UPDATE products SET stock_quantity=5 WHERE id=?", productId);
        jdbc.update("DELETE FROM audit_logs WHERE target_id=? AND action='ORDER_DEDUCT_STOCK'", orderId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Order cached = entityManager.find(Order.class, orderId);
            entityManager.find(Product.class, productId);
            assertThat(cached.getStockDeductedAt()).isNull();

            TransactionTemplate separate = new TransactionTemplate(transactionManager);
            separate.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
            separate.executeWithoutResult(inner -> stockMutations.deductOrder(orderId));

            // The cached entity is still stale; the engine must refresh its marker under a lock before proceeding.
            assertThat(cached.getStockDeductedAt()).isNull();
            stockMutations.deductOrder(orderId);
            assertThat(cached.getStockDeductedAt()).isNotNull();
        });
        assertThat(stock(productId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='ORDER_DEDUCT_STOCK'",
                Integer.class, orderId)).isEqualTo(1);
    }

    /** Stock audit failure must roll back order and item rows saved by checkout before calling the engine. */
    @Test void stockAuditFailureRollsBackNewOrderAndDeductions() {
        long productId = product(5, "100000");
        Buyer buyer = buyer(List.of(productId), List.of(2));
        Attempt attempt = attempt(buyer, Order.PaymentMethod.VNPAY);
        doThrow(new IllegalStateException("QA stock audit failure")).when(audit)
                .record(eq(buyer.id()), eq("ORDER_DEDUCT_STOCK"), anyString(), anyLong(), anyString(), isNull());
        assertThatThrownBy(() -> service.placeOrder(buyer.id(), attempt.request(), attempt.confirmation()))
                .isInstanceOf(IllegalStateException.class);
        assertNoOrder(buyer);
        assertThat(stock(productId)).isEqualTo(5);
        assertThat(cartLines(buyer)).isEqualTo(1);
    }

    /** Fail after flush to verify rollback of stock, order/items, and cart deletion. */
    @Test void lateAuditFailureRollsBackStockOrderAndCartDeletion() {
        long productId = product(5, "100000");
        Buyer buyer = buyer(List.of(productId), List.of(2));
        Attempt attempt = attempt(buyer, Order.PaymentMethod.COD);
        doThrow(new IllegalStateException("QA audit failure")).when(audit)
                .record(eq(buyer.id()), eq("ORDER_PLACED"), anyString(), anyLong(), anyString(), isNull());
        assertThatThrownBy(() -> service.placeOrder(buyer.id(), attempt.request(), attempt.confirmation()))
                .isInstanceOf(IllegalStateException.class);
        assertNoOrder(buyer);
        assertThat(stock(productId)).isEqualTo(5);
        assertThat(cartLines(buyer)).isEqualTo(1);
    }

    @Test void priceAndDeliveryChangesRequireNewConfirmation() {
        long productId = product(5, "100000");
        Buyer buyer = buyer(List.of(productId), List.of(1));
        Attempt attempt = attempt(buyer, Order.PaymentMethod.COD);
        jdbc.update("UPDATE products SET price=110000 WHERE id=?", productId);
        assertFailure(ErrorCode.CHECKOUT_CHANGED, buyer, attempt);
        Attempt next = attempt(buyer, Order.PaymentMethod.COD);
        jdbc.update("UPDATE shipping_addresses SET detailed_address='Địa chỉ mới' WHERE id=?", buyer.addressId());
        assertFailure(ErrorCode.CHECKOUT_CHANGED, buyer, next);
        assertNoOrder(buyer);
        assertThat(stock(productId)).isEqualTo(5);
    }

    /** Start two buyers together using a latch; only one may purchase the last stock unit. */
    @Test void twoBuyersCompetingForLastUnitProduceOnlyOneOrder() throws Exception {
        long productId = product(1, "100000");
        Buyer first = buyer(List.of(productId), List.of(1));
        Buyer second = buyer(List.of(productId), List.of(1));
        Attempt a = attempt(first, Order.PaymentMethod.COD);
        Attempt b = attempt(second, Order.PaymentMethod.VNPAY);
        List<Object> results = concurrent(first, a, second, b);
        assertThat(results.stream().filter(String.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(AppException.class::isInstance)
                .map(AppException.class::cast).map(AppException::getErrorCode))
                .containsExactly(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(stock(productId)).isZero();
        assertThat(cartLines(first) + cartLines(second)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id IN (?,?)",
                Integer.class, first.id(), second.id())).isEqualTo(1);
    }

    /** Concurrent requests with one token must return one order; later replays must preserve newly added cart items. */
    @Test void concurrentDuplicateSubmissionReturnsSameOrderAndDoesNotClearRefilledCart() throws Exception {
        long productId = product(5, "100000");
        Buyer buyer = buyer(List.of(productId), List.of(2));
        Attempt attempt = attempt(buyer, Order.PaymentMethod.COD);
        assertThat(concurrent(buyer, attempt, buyer, attempt))
                .containsExactly(attempt.request().getCheckoutToken(), attempt.request().getCheckoutToken());
        assertThat(stock(productId)).isEqualTo(3);
        jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity) VALUES(?,?,1)", buyer.cartId(), productId);
        assertThat(service.placeOrder(buyer.id(), attempt.request(), null)).isEqualTo(attempt.request().getCheckoutToken());
        assertThat(stock(productId)).isEqualTo(3);
        assertThat(cartLines(buyer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE user_id=? AND action='ORDER_PLACED'",
                Integer.class, buyer.id())).isEqualTo(1);
    }

    @Test void phoneVerificationRevokedAfterReviewBlocksPosting() {
        long productId = product(5, "100000");
        Buyer buyer = buyer(List.of(productId), List.of(1));
        Attempt reviewed = attempt(buyer, Order.PaymentMethod.COD);
        jdbc.update("UPDATE users SET phone_verified=FALSE WHERE id=?", buyer.id());
        assertFailure(ErrorCode.PHONE_REQUIRED_BEFORE_CHECKOUT, buyer, reviewed);
        assertNoOrder(buyer);
        assertThat(stock(productId)).isEqualTo(5);
        assertThat(cartLines(buyer)).isEqualTo(1);
    }

    @Test void foreignAddressIsRejectedForVerifiedCustomer() {
        long productId = product(5, "100000");
        Buyer buyer = buyer(List.of(productId), List.of(1));
        Attempt attempt = attempt(buyer, Order.PaymentMethod.COD);
        Buyer other = buyer(List.of(productId), List.of(1));
        assertThatThrownBy(() -> service.getCheckoutView(buyer.id(), other.addressId()))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(ErrorCode.ADDRESS_NOT_FOUND);
        attempt.request().setShippingAddressId(other.addressId());
        assertFailure(ErrorCode.ADDRESS_NOT_FOUND, buyer, new Attempt(attempt.request(),
                new CheckoutConfirmation(attempt.confirmation().checkoutToken(), buyer.id(),
                        service.getCheckoutView(other.id(), other.addressId()), Order.PaymentMethod.COD, null)));
        assertNoOrder(buyer);
        assertThat(stock(productId)).isEqualTo(5);
    }

    @Test void staffHappyPathAndQueueFiltersUseRealDatabase() {
        long staffId = saleStaff();
        long id = placedCodOrder();
        transition(id, staffId, Order.OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NOT NULL FROM orders WHERE id=?", Boolean.class, id)).isTrue();
        var queue = stateMachine.getOrderQueue(new OrderQueueFilter(Order.OrderStatus.CONFIRMED, Order.PaymentStatus.UNPAID,
                Order.PaymentMethod.COD, java.time.LocalDate.now(), java.time.LocalDate.now()), PageRequest.of(0, 100), staffId);
        assertThat(queue.getContent()).extracting("id").contains(id);
        var checklist = stateMachine.getPackagingChecklist(id, staffId);
        assertThat(checklist.order().recipientName()).isEqualTo("QA Recipient");
        assertThat(checklist.order().fullAddress()).startsWith("12 Đội Cấn");
        assertThat(checklist.order().items()).hasSize(1);
        transition(id, staffId, Order.OrderStatus.SHIPPING);
        transition(id, staffId, Order.OrderStatus.DELIVERED);
        assertThat(jdbc.queryForObject("SELECT shipped_at IS NOT NULL AND delivered_at IS NOT NULL FROM orders WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT tracking_number FROM orders WHERE id=?", String.class, id)).isEqualTo("TRACK-QA");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='ORDER_STATUS_CHANGED'", Integer.class, id)).isEqualTo(3);
        assertThatThrownBy(() -> transition(id, staffId, Order.OrderStatus.PENDING_CONFIRMATION)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test void staffCancellationRestoresStockOnceAndPersistsReasonAndAudit() {
        long staffId = saleStaff();
        long id = placedCodOrder();
        long productId = jdbc.queryForObject("SELECT product_id FROM order_items WHERE order_id=?", Long.class, id);
        assertThat(stock(productId)).isEqualTo(8);
        transition(id, staffId, Order.OrderStatus.CANCELLED);
        assertThat(stock(productId)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id=?", String.class, id)).isEqualTo("QA cancellation");
        assertThat(jdbc.queryForObject("SELECT stock_restored_by_user_id FROM orders WHERE id=?", Long.class, id)).isEqualTo(staffId);
        assertThat(jdbc.queryForObject("SELECT cancelled_at IS NOT NULL AND stock_restored_at IS NOT NULL FROM orders WHERE id=?", Boolean.class, id)).isTrue();
        assertThatThrownBy(() -> transition(id, staffId, Order.OrderStatus.CANCELLED)).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(stock(productId)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='ORDER_STATUS_CHANGED'", Integer.class, id)).isEqualTo(1);
    }

    @Test void staffAuditFailureRollsBackCancellationAndRestock() {
        long staffId = saleStaff();
        long id = placedCodOrder();
        long productId = jdbc.queryForObject("SELECT product_id FROM order_items WHERE order_id=?", Long.class, id);
        doThrow(new IllegalStateException("forced audit failure")).when(audit)
                .recordRequired(eq(staffId), eq("ORDER_STATUS_CHANGED"), eq("orders"), eq(id), anyString(), isNull());
        assertThatThrownBy(() -> transition(id, staffId, Order.OrderStatus.CANCELLED)).isInstanceOf(IllegalStateException.class);
        assertThat(stock(productId)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id=?", String.class, id)).isEqualTo("PENDING_CONFIRMATION");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id=?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='ORDER_CANCELLED_RESTOCK'", Integer.class, id)).isZero();
    }

    @Test void staffConcurrentApprovalLocksAndValidatesLatestState() throws Exception {
        long staffA = saleStaff(); long staffB = saleStaff(); long id = placedCodOrder();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> staffAfter(start, id, staffA));
            var b = executor.submit(() -> staffAfter(start, id, staffB));
            start.countDown();
            var results = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            assertThat(results.stream().filter("OK"::equals)).hasSize(1);
            assertThat(results.stream().filter(IllegalStateTransitionException.class::isInstance)).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id=?", String.class, id)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='ORDER_STATUS_CHANGED'", Integer.class, id)).isEqualTo(1);
    }

    @Test void staffRefreshesCachedOrderUnderLockBeforeCheckingState() {
        long staffId = saleStaff(); long id = placedCodOrder();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Order cached = entityManager.find(Order.class, id);
            TransactionTemplate separate = new TransactionTemplate(transactionManager);
            separate.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
            separate.executeWithoutResult(inner -> transition(id, staffId, Order.OrderStatus.CONFIRMED));
            assertThat(cached.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
            transition(id, staffId, Order.OrderStatus.CONFIRMED);
        })).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='ORDER_STATUS_CHANGED'", Integer.class, id)).isEqualTo(1);
    }

    private Object staffAfter(CountDownLatch start, long id, long staff) throws InterruptedException {
        start.await();
        try {
            stateMachine.transitionStatus(id, new OrderTransitionRequest(Order.OrderStatus.CONFIRMED, null, null, null), staff);
            return "OK";
        } catch (RuntimeException exception) { return exception; }
    }

    @Test void staffQueueExcludesPendingVnpayIncludingExplicitFilterAndDirectDetail() {
        long staff = saleStaff();
        long product = product(10, "100000");
        Buyer buyer = buyer(List.of(product), List.of(1));
        Attempt pending = attempt(buyer, Order.PaymentMethod.VNPAY);
        service.placeOrder(buyer.id(), pending.request(), pending.confirmation());
        long id = orderId(pending);
        var all = stateMachine.getOrderQueue(new OrderQueueFilter(null, null, null, null, null), PageRequest.of(0, 100), staff);
        assertThat(all.getContent()).extracting("id").doesNotContain(id);
        var explicitlyPending = stateMachine.getOrderQueue(new OrderQueueFilter(Order.OrderStatus.PENDING_PAYMENT, null, null, null, null), PageRequest.of(0, 100), staff);
        assertThat(explicitlyPending.getContent()).isEmpty();
        assertThatThrownBy(() -> stateMachine.getPackagingChecklist(id, staff)).isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test void staffCodDeliveryRequiresCollectionAndCommitsPaidTogether() {
        long staff = saleStaff(); long id = placedCodOrder();
        transition(id, staff, Order.OrderStatus.CONFIRMED); transition(id, staff, Order.OrderStatus.SHIPPING);
        assertThatThrownBy(() -> stateMachine.transitionStatus(id,
                new OrderTransitionRequest(Order.OrderStatus.DELIVERED, null, null, false), staff)).isInstanceOf(AppException.class);
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id=?", String.class, id)).isEqualTo("SHIPPING");
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, id)).isEqualTo("UNPAID");
        transition(id, staff, Order.OrderStatus.DELIVERED);
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, id)).isEqualTo("PAID");
    }

    @Test void staffFullRefundPersistsEvidenceAndDoesNotRestockAgain() {
        long staff = saleStaff(); long id = cancelledPaidOrder(staff);
        long product = jdbc.queryForObject("SELECT product_id FROM order_items WHERE order_id=?", Long.class, id);
        int before = stock(product);
        var request = refundRequest(id);
        stateMachine.confirmExternalRefund(id, request, staff);
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, id)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT order_status FROM orders WHERE id=?", String.class, id)).isEqualTo("CANCELLED");
        assertThat(stock(product)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(details_json,'$.reference')) FROM audit_logs WHERE target_id=? AND action='ORDER_REFUND_CONFIRMED'", String.class, id)).isEqualTo("QA-REFUND-" + id);
        assertThat(stateMachine.getPackagingChecklist(id, staff).history()).extracting("action").contains("ORDER_REFUND_CONFIRMED");
        assertThatThrownBy(() -> stateMachine.confirmExternalRefund(id, request, staff)).isInstanceOf(AppException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='ORDER_REFUND_CONFIRMED'", Integer.class, id)).isEqualTo(1);
    }

    @Test void staffRefundAuditFailureRollsBackPayment() {
        long staff = saleStaff(); long id = cancelledPaidOrder(staff);
        doThrow(new IllegalStateException("forced required audit failure")).when(audit)
                .recordRequired(eq(staff), eq("ORDER_REFUND_CONFIRMED"), eq("orders"), eq(id), anyString(), isNull());
        assertThatThrownBy(() -> stateMachine.confirmExternalRefund(id, refundRequest(id), staff)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, id)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='ORDER_REFUND_CONFIRMED'", Integer.class, id)).isZero();
    }

    @Test void staffConcurrentRefundConfirmsExactlyOnce() throws Exception {
        long first = saleStaff(); long second = saleStaff(); long id = cancelledPaidOrder(first);
        var request = refundRequest(id);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> refundAfter(start, id, first, request));
            var b = executor.submit(() -> refundAfter(start, id, second, request));
            start.countDown();
            var results = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            assertThat(results.stream().filter("OK"::equals)).hasSize(1);
            assertThat(results.stream().filter(AppException.class::isInstance)).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='ORDER_REFUND_CONFIRMED'", Integer.class, id)).isEqualTo(1);
    }

    private Object refundAfter(CountDownLatch start, long id, long actor, RefundConfirmationRequest request) throws InterruptedException {
        start.await();
        try { stateMachine.confirmExternalRefund(id, request, actor); return "OK"; }
        catch (RuntimeException exception) { return exception; }
    }
    private long cancelledPaidOrder(long staff) {
        long id = placedCodOrder();
        jdbc.update("UPDATE orders SET payment_method='VNPAY', payment_status='PAID', vnpay_txn_ref=? WHERE id=?", "QA-PAID-" + id, id);
        transition(id, staff, Order.OrderStatus.CANCELLED);
        return id;
    }
    private RefundConfirmationRequest refundRequest(long id) {
        return new RefundConfirmationRequest(jdbc.queryForObject("SELECT final_total_amount FROM orders WHERE id=?", java.math.BigDecimal.class, id),
                "BANK_TRANSFER", java.time.LocalDate.now(), "QA-REFUND-" + id, "QA bank receipt", true);
    }
    private long saleStaff() {
        jdbc.update("INSERT INTO roles(role_name) VALUES('ROLE_SALE_STAFF') ON DUPLICATE KEY UPDATE role_name=role_name");
        long role = jdbc.queryForObject("SELECT id FROM roles WHERE role_name='ROLE_SALE_STAFF'", Long.class);
        return insert("INSERT INTO users(role_id,password_hash,full_name) VALUES(?,'qa-hash','QA Staff')", role);
    }
    private long placedCodOrder() {
        long productId = product(10, "100000");
        Buyer buyer = buyer(List.of(productId), List.of(2));
        Attempt attempt = attempt(buyer, Order.PaymentMethod.COD);
        service.placeOrder(buyer.id(), attempt.request(), attempt.confirmation());
        return orderId(attempt);
    }
    private void transition(long id, long staff, Order.OrderStatus target) {
        stateMachine.transitionStatus(id, new OrderTransitionRequest(target, "QA cancellation", "TRACK-QA", target == Order.OrderStatus.DELIVERED), staff);
    }

    private List<Object> concurrent(Buyer first, Attempt a, Buyer second, Attempt b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> runAfter(start, first, a));
            var two = executor.submit(() -> runAfter(start, second, b));
            start.countDown();
            return List.of(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
        }
    }

    private Object runAfter(CountDownLatch start, Buyer buyer, Attempt attempt) throws InterruptedException {
        start.await();
        try {
            return service.placeOrder(buyer.id(), attempt.request(), attempt.confirmation());
        } catch (AppException exception) { return exception; }
    }

    private void assertFailure(ErrorCode code, Buyer buyer, Attempt attempt) {
        assertThatThrownBy(() -> service.placeOrder(buyer.id(), attempt.request(), attempt.confirmation()))
                .isInstanceOf(AppException.class).extracting("errorCode").isEqualTo(code);
    }

    private void assertNoOrder(Buyer buyer) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyer.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items oi JOIN orders o ON o.id=oi.order_id WHERE o.user_id=?",
                Integer.class, buyer.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE user_id=? AND action='ORDER_PLACED'",
                Integer.class, buyer.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE user_id=? AND action='ORDER_DEDUCT_STOCK'",
                Integer.class, buyer.id())).isZero();
    }

    private long product(int stock, String price) {
        return insert("""
                INSERT INTO products(barcode,title,category_id,product_type,price,original_cost,stock_quantity)
                VALUES(?,? ,?,'BOOK',?,10000,?)
                """, UUID.randomUUID().toString(), "Sách QA " + sequence.incrementAndGet(), categoryId, price, stock);
    }

    private Buyer buyer(List<Long> ids, List<Integer> quantities) {
        long userId = insert("""
                INSERT INTO users(role_id,password_hash,full_name,phone,phone_verified)
                VALUES(?,'qa-hash','QA Customer',?,TRUE)
                """, roleId, "09" + String.format("%08d", sequence.incrementAndGet()));
        long addressId = insert("""
                INSERT INTO shipping_addresses(user_id,recipient_name,phone,economic_region,province,district,ward,detailed_address,is_default)
                VALUES(?,'QA Recipient','0987654321',?,'Hà Nội','Ba Đình','Đội Cấn','12 Đội Cấn',TRUE)
                """, userId, EconomicRegion.NORTHERN.name());
        long cartId = insert("INSERT INTO carts(user_id) VALUES(?)", userId);
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity) VALUES(?,?,?)", cartId, ids.get(i), quantities.get(i));
        }
        return new Buyer(userId, addressId, cartId);
    }

    private Attempt attempt(Buyer buyer, Order.PaymentMethod method) {
        CheckoutRequest request = new CheckoutRequest();
        request.setCheckoutToken(UUID.randomUUID().toString().replace("-", ""));
        request.setShippingAddressId(buyer.addressId()); request.setPaymentMethod(method);
        return new Attempt(request, new CheckoutConfirmation(request.getCheckoutToken(), buyer.id(),
                service.getCheckoutView(buyer.id(), buyer.addressId()), method, null));
    }

    private long insert(String sql, Object... values) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private int stock(long productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, productId);
    }
    private int cartLines(Buyer buyer) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id=?", Integer.class, buyer.cartId());
    }
    private long orderId(Attempt attempt) {
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_code=?", Long.class, attempt.request().getCheckoutToken());
    }
    private record Buyer(long id, long addressId, long cartId) {}
    private record Attempt(CheckoutRequest request, CheckoutConfirmation confirmation) {}
}
