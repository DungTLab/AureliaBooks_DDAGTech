package com.ddagtech.aureliabooks.service;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Exercise real Security, MVC, service, and MySQL integration, including missing account phones and delivery-phone snapshots. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "checkout.mysql", matches = "true")
class CheckoutFlowMySqlTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    VoucherService vouchers;

    @Autowired
    StockMutationService stock;

    @Autowired
    OrderPlacementService placement;

    @Autowired
    java.time.Clock clock;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    AuditLogService audit;

    private long owner;
    private long address;
    private long product;
    private long cart;
    private CustomUserDetails principal;
    private MockHttpSession session;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        OrderPlacementMySqlTest.database(registry);
    }

    @AfterAll
    static void cleanup() throws Exception {
        OrderPlacementMySqlTest.dropOnlyCreatedQaDatabase();
    }

    @BeforeEach
    void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("uc11_qa_");
        jdbc.update("INSERT INTO roles(role_name) VALUES('ROLE_CUSTOMER') ON DUPLICATE KEY UPDATE role_name=role_name");
        long role = jdbc.queryForObject("SELECT id FROM roles WHERE role_name='ROLE_CUSTOMER'", Long.class);
        String phone = "09" + String.format("%08d", Math.floorMod(UUID.randomUUID().hashCode(), 100000000));
        owner = insert(
            "INSERT INTO users(role_id,password_hash,full_name,phone,phone_verified) VALUES(?,'qa-hash','Nguyễn Minh Anh',?,TRUE)",
            role,
            phone
        );
        long category = insert("INSERT INTO categories(name) VALUES(?)", "QA " + UUID.randomUUID());
        product = insert(
            """
            INSERT INTO products(barcode,title,category_id,product_type,price,original_cost,stock_quantity)
            VALUES(?,'Đi tìm lẽ sống',?,'BOOK',100000,50000,10)
            """,
            UUID.randomUUID().toString(),
            category
        );
        address = insert(
            """
            INSERT INTO shipping_addresses(user_id,recipient_name,phone,economic_region,province,district,ward,detailed_address,is_default)
            VALUES(?,'Nguyễn Minh Anh','0912345678','NORTHERN','Hà Nội','Ba Đình','Đội Cấn','12 Đội Cấn',TRUE)
            """,
            owner
        );
        cart = insert("INSERT INTO carts(user_id) VALUES(?)", owner);
        jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity) VALUES(?,?,2)", cart, product);
        principal = new CustomUserDetails(users.findById(owner).orElseThrow());
        session = new MockHttpSession();
    }

    /** Complete COD checkout without an account phone; preserve the delivery phone and prevent duplicate orders. */
    @Test
    void customerCompletesCodCheckoutAndReplayKeepsSingleOrder() throws Exception {
        jdbc.update("UPDATE users SET phone=NULL, phone_verified=FALSE WHERE id=?", owner);
        principal = new CustomUserDetails(users.findById(owner).orElseThrow());
        String html = mvc
            .perform(get("/cart").session(session).with(user(principal)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/checkout")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        capture("cart.html", html);
        capture(
            "checkout.html",
            mvc
                .perform(get("/checkout").session(session).with(user(principal)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
        CheckoutConfirmation review = prepare();
        String token = review.checkoutToken();
        mvc.perform(
            post("/checkout/place")
                .session(session)
                .with(user(principal))
                .with(csrf())
                .param("checkoutToken", token)
                .param("shippingAddressId", Long.toString(address))
                .param("paymentMethod", "COD")
                .param("finalTotalAmount", "1")
        ).andExpect(redirectedUrl("/orders/" + token));
        capture(
            "order-confirm.html",
            mvc
                .perform(
                    get("/orders/" + token)
                        .session(session)
                        .with(user(principal))
                )
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chờ xác nhận")))
                .andExpect(content().string(containsString("Chưa thanh toán")))
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
        assertThat(
            jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, product)
        ).isEqualTo(8);
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id=?", Integer.class, cart)
        ).isZero();
        mvc.perform(
            post("/checkout/place")
                .session(session)
                .with(user(principal))
                .with(csrf())
                .param("checkoutToken", token)
                .param("shippingAddressId", Long.toString(address))
                .param("paymentMethod", "COD")
        ).andExpect(redirectedUrl("/orders/" + token));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, owner)).isEqualTo(
            1
        );
        assertThat(
            jdbc.queryForObject(
                "SELECT final_total_amount FROM orders WHERE order_code=?",
                java.math.BigDecimal.class,
                token
            )
        ).isEqualByComparingTo("250000");
        assertThat(
            jdbc.queryForObject("SELECT shipping_phone FROM orders WHERE order_code=?", String.class, token)
        ).isEqualTo("0912345678");
        long other = insert(
            "INSERT INTO users(role_id,password_hash,full_name,phone,phone_verified) SELECT role_id,'qa-hash','Other',NULL,TRUE FROM users WHERE id=?",
            owner
        );
        var otherPrincipal = new CustomUserDetails(users.findById(other).orElseThrow());
        mvc.perform(get("/orders/" + token).with(user(otherPrincipal))).andExpect(status().isNotFound());
    }

    /** Change the price after review: reject the old request and issue a new review with updated totals and token. */
    @Test
    void stalePriceRequiresNewReviewBeforeAnyOrderOrStockChange() throws Exception {
        CheckoutConfirmation review = prepare();
        jdbc.update("UPDATE products SET price=110000 WHERE id=?", product);
        mvc.perform(
            post("/checkout/place")
                .session(session)
                .with(user(principal))
                .with(csrf())
                .param("checkoutToken", review.checkoutToken())
                .param("shippingAddressId", Long.toString(address))
                .param("paymentMethod", "COD")
        )
            .andExpect(redirectedUrl("/checkout"))
            .andExpect(flash().attributeExists("errorMessage"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, owner)).isZero();
        assertThat(
            jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, product)
        ).isEqualTo(10);
        CheckoutConfirmation next = prepare();
        assertThat(next.checkoutToken()).isNotEqualTo(review.checkoutToken());
        assertThat(next.reviewedCheckout().finalTotalAmount()).isEqualByComparingTo("270000");
    }

    @Test
    void inlineAddressCanBeAddedAndSelectedWithoutLeavingCheckout() throws Exception {
        mvc.perform(
            post("/checkout/addresses")
                .session(session)
                .with(user(principal))
                .with(csrf())
                .param("recipientName", "Người nhận mới")
                .param("phone", "0987654321")
                .param("provinceCode", "79")
                .param("economicRegion", "NORTHERN")
                .param("ward", "Bến Nghé")
                .param("detailedAddress", "12 Lê Lợi")
        ).andExpect(status().is3xxRedirection());
        long selected = jdbc.queryForObject(
            "SELECT id FROM shipping_addresses WHERE user_id=? AND economic_region='SOUTHERN_MEKONG'",
            Long.class,
            owner
        );
        mvc.perform(
            get("/checkout").session(session).with(user(principal)).param("shippingAddressId", Long.toString(selected))
        )
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("230.000")));
        assertThat(
            jdbc.queryForObject("SELECT district FROM shipping_addresses WHERE id=?", String.class, selected)
        ).isNull();
        address = selected;
        var review = prepare();
        mvc.perform(
            post("/checkout/place")
                .session(session)
                .with(user(principal))
                .with(csrf())
                .param("checkoutToken", review.checkoutToken())
                .param("shippingAddressId", Long.toString(selected))
                .param("paymentMethod", "COD")
        ).andExpect(status().is3xxRedirection());
        String snapshot = jdbc.queryForObject(
            "SELECT shipping_full_address FROM orders WHERE order_code=?",
            String.class,
            review.checkoutToken()
        );
        assertThat(snapshot).contains("12 Lê Lợi", "Bến Nghé", "Thành phố Hồ Chí Minh").doesNotContain("null", ", ,");
    }

    @Test
    void unavailableVnpayCannotCreateOrder() throws Exception {
        mvc.perform(
            post("/checkout/review")
                .session(session)
                .with(user(principal))
                .with(csrf())
                .param("shippingAddressId", Long.toString(address))
                .param("paymentMethod", "VNPAY")
        ).andExpect(redirectedUrl("/checkout"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, owner)).isZero();
    }

    @Test
    void percentageVoucherFlowsThroughPreviewReviewPlacementReplayAndCancellation() throws Exception {
        jdbc.update("UPDATE products SET price=250000 WHERE id=?", product);
        String code = campaign(
            com.ddagtech.aureliabooks.entity.Voucher.Scope.PRODUCT,
            com.ddagtech.aureliabooks.entity.Voucher.Type.PERCENTAGE,
            "10",
            "50000",
            1
        );
        capture(
            "voucher-checkout.html",
            mvc
                .perform(
                    get("/checkout")
                        .with(user(principal))
                        .param("voucherCode", code)
                        .param("shippingAddressId", Long.toString(address))
                )
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("500.000")))
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
        assertThat(remaining(code)).isEqualTo(1);
        CheckoutConfirmation review = prepare(code);
        assertThat(review.reviewedCheckout().productDiscount()).isEqualByComparingTo("50000");
        assertThat(review.reviewedCheckout().finalTotalAmount()).isEqualByComparingTo("500000");
        place(review, code);
        Long id = jdbc.queryForObject("SELECT id FROM orders WHERE order_code=?", Long.class, review.checkoutToken());
        assertThat(
            jdbc.queryForObject("SELECT final_total_amount FROM orders WHERE id=?", java.math.BigDecimal.class, id)
        ).isEqualByComparingTo("500000");
        assertThat(remaining(code)).isZero();
        place(review, code); // Replay has no session review, yet returns the same committed order.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, owner)).isEqualTo(
            1
        );
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM order_vouchers WHERE order_id=?", Integer.class, id)
        ).isEqualTo(1);
        stock.restoreCancelledOrder(id, owner);
        stock.restoreCancelledOrder(id, owner);
        assertThat(remaining(code)).isEqualTo(1);
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM order_vouchers WHERE order_id=?", Integer.class, id)
        ).isZero();
        assertThat(
            jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, product)
        ).isEqualTo(10);
        jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity) VALUES(?,?,2)", cart, product);
        place(prepare(code), code); // Cancellation releases the account's one-use restriction.
        assertThat(remaining(code)).isZero();
    }

    @Test
    void fixedAndShippingVouchersPersistExactlyTheReviewedTotal() throws Exception {
        String code = campaign(
            com.ddagtech.aureliabooks.entity.Voucher.Scope.SHIPPING,
            com.ddagtech.aureliabooks.entity.Voucher.Type.FIXED_AMOUNT,
            "10000",
            null,
            2
        );
        CheckoutConfirmation review = prepare(code);
        assertThat(review.reviewedCheckout().shippingFee()).isEqualByComparingTo("40000");
        assertThat(review.reviewedCheckout().finalTotalAmount()).isEqualByComparingTo("240000");
        place(review, code);
        assertThat(
            jdbc.queryForObject(
                "SELECT final_total_amount FROM orders WHERE order_code=?",
                java.math.BigDecimal.class,
                review.checkoutToken()
            )
        ).isEqualByComparingTo("240000");
        assertThat(
            jdbc.queryForObject(
                "SELECT shipping_fee FROM orders WHERE order_code=?",
                java.math.BigDecimal.class,
                review.checkoutToken()
            )
        ).isEqualByComparingTo("50000");
        assertThat(
            jdbc.queryForObject(
                "SELECT discount_applied FROM order_vouchers WHERE order_id=(SELECT id FROM orders WHERE order_code=?)",
                java.math.BigDecimal.class,
                review.checkoutToken()
            )
        ).isEqualByComparingTo("10000");
    }

    @Test
    void fixedVoucherOn300kAndVnpayOrderKeepDiscountedSnapshot() throws Exception {
        jdbc.update("UPDATE products SET price=150000 WHERE id=?", product);
        String code = campaign(
            com.ddagtech.aureliabooks.entity.Voucher.Scope.PRODUCT,
            com.ddagtech.aureliabooks.entity.Voucher.Type.FIXED_AMOUNT,
            "50000",
            null,
            1
        );
        var checkout = placement.getCheckoutView(owner, address, code);
        assertThat(checkout.productDiscount()).isEqualByComparingTo("50000");
        assertThat(checkout.finalTotalAmount()).isEqualByComparingTo("300000");
        var review = new CheckoutConfirmation(
            UUID.randomUUID().toString().replace("-", ""),
            owner,
            checkout,
            com.ddagtech.aureliabooks.entity.Order.PaymentMethod.VNPAY,
            code
        );
        var form = new com.ddagtech.aureliabooks.dto.request.CheckoutRequest();
        form.setCheckoutToken(review.checkoutToken());
        form.setShippingAddressId(address);
        form.setPaymentMethod(review.paymentMethod());
        form.setVoucherCode(code);
        placement.placeOrder(owner, form, review);
        assertThat(
            jdbc.queryForObject(
                "SELECT final_total_amount FROM orders WHERE order_code=?",
                java.math.BigDecimal.class,
                review.checkoutToken()
            )
        ).isEqualByComparingTo("300000");
        assertThat(
            jdbc.queryForObject(
                "SELECT order_status FROM orders WHERE order_code=?",
                String.class,
                review.checkoutToken()
            )
        ).isEqualTo("PENDING_PAYMENT");
        assertThat(remaining(code)).isZero();
    }

    @Test
    void auditFailureRollsBackQuotaOrderStockCartAndRedemption() throws Exception {
        String code = campaign(
            com.ddagtech.aureliabooks.entity.Voucher.Scope.PRODUCT,
            com.ddagtech.aureliabooks.entity.Voucher.Type.FIXED_AMOUNT,
            "50000",
            null,
            1
        );
        CheckoutConfirmation review = prepare(code);
        org.mockito.Mockito.doThrow(new IllegalStateException("QA late placement failure"))
            .when(audit)
            .record(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("ORDER_PLACED"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
            );
        mvc.perform(
            post("/checkout/place")
                .session(session)
                .with(user(principal))
                .with(csrf())
                .param("checkoutToken", review.checkoutToken())
                .param("shippingAddressId", Long.toString(address))
                .param("paymentMethod", "COD")
                .param("voucherCode", code)
        ).andExpect(redirectedUrl("/checkout"));
        assertThat(remaining(code)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, owner)).isZero();
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_vouchers ov JOIN vouchers v ON v.id=ov.voucher_id WHERE v.code=?",
                Integer.class,
                code
            )
        ).isZero();
        assertThat(
            jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, product)
        ).isEqualTo(10);
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id=?", Integer.class, cart)
        ).isEqualTo(1);
    }

    @Test
    void voucherExpiredAfterReviewCannotCreateOrder() throws Exception {
        String code = campaign(
            com.ddagtech.aureliabooks.entity.Voucher.Scope.PRODUCT,
            com.ddagtech.aureliabooks.entity.Voucher.Type.FIXED_AMOUNT,
            "50000",
            null,
            1
        );
        CheckoutConfirmation review = prepare(code);
        jdbc.update(
            "UPDATE vouchers SET start_date=DATE_SUB(NOW(),INTERVAL 2 DAY),end_date=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE code=?",
            code
        );
        mvc.perform(
            post("/checkout/place")
                .session(session)
                .with(user(principal))
                .with(csrf())
                .param("checkoutToken", review.checkoutToken())
                .param("shippingAddressId", Long.toString(address))
                .param("paymentMethod", "COD")
                .param("voucherCode", code)
        ).andExpect(redirectedUrl("/checkout"));
        assertThat(remaining(code)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, owner)).isZero();
    }

    @Test
    void concurrentCheckoutForLastVoucherUseCommitsOnlyOneOrder() throws Exception {
        String code = campaign(
            com.ddagtech.aureliabooks.entity.Voucher.Scope.PRODUCT,
            com.ddagtech.aureliabooks.entity.Voucher.Type.FIXED_AMOUNT,
            "50000",
            null,
            1
        );
        CheckoutConfirmation first = prepare(code);
        long secondOwner = insert(
            "INSERT INTO users(role_id,password_hash,full_name) SELECT role_id,'qa-hash','Second' FROM users WHERE id=?",
            owner
        );
        long secondAddress = insert(
            "INSERT INTO shipping_addresses(user_id,recipient_name,phone,economic_region,province,district,ward,detailed_address,is_default) VALUES(?,'Second','0912345678','NORTHERN','HN','BD','DC','12 DC',TRUE)",
            secondOwner
        );
        long secondCart = insert("INSERT INTO carts(user_id) VALUES(?)", secondOwner);
        jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity) VALUES(?,?,2)", secondCart, product);
        var view = placement.getCheckoutView(secondOwner, secondAddress, code);
        CheckoutConfirmation second = new CheckoutConfirmation(
            UUID.randomUUID().toString().replace("-", ""),
            secondOwner,
            view,
            com.ddagtech.aureliabooks.entity.Order.PaymentMethod.COD,
            code
        );
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> concurrentPlace(first, code, start));
            var b = pool.submit(() -> concurrentPlace(second, code, start));
            assertThat(
                a.get(20, java.util.concurrent.TimeUnit.SECONDS) + b.get(20, java.util.concurrent.TimeUnit.SECONDS)
            ).isEqualTo(1);
        }
        assertThat(remaining(code)).isZero();
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id IN (?,?)", Integer.class, owner, secondOwner)
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_vouchers ov JOIN vouchers v ON ov.voucher_id=v.id WHERE v.code=?",
                Integer.class,
                code
            )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, product)
        ).isEqualTo(8);
    }

    private int concurrentPlace(CheckoutConfirmation review, String code, java.util.concurrent.CyclicBarrier start)
        throws Exception {
        start.await(10, java.util.concurrent.TimeUnit.SECONDS);
        var form = new com.ddagtech.aureliabooks.dto.request.CheckoutRequest();
        form.setCheckoutToken(review.checkoutToken());
        form.setShippingAddressId(review.reviewedCheckout().selectedAddressId());
        form.setPaymentMethod(review.paymentMethod());
        form.setVoucherCode(code);
        try {
            placement.placeOrder(review.userId(), form, review);
            return 1;
        } catch (com.ddagtech.aureliabooks.exception.AppException rejected) {
            return 0;
        }
    }

    private String campaign(
        com.ddagtech.aureliabooks.entity.Voucher.Scope scope,
        com.ddagtech.aureliabooks.entity.Voucher.Type type,
        String amount,
        String cap,
        int uses
    ) {
        jdbc.update("INSERT INTO roles(role_name) VALUES('ROLE_MANAGER') ON DUPLICATE KEY UPDATE role_name=role_name");
        long role = jdbc.queryForObject("SELECT id FROM roles WHERE role_name='ROLE_MANAGER'", Long.class);
        long manager = insert("INSERT INTO users(role_id,password_hash,full_name) VALUES(?,'qa-hash','Manager')", role);
        String code = "QA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        var now = java.time.LocalDateTime.now(clock);
        vouchers.create(
            manager,
            new com.ddagtech.aureliabooks.dto.request.VoucherForm(
                code,
                "QA campaign",
                scope,
                type,
                type == com.ddagtech.aureliabooks.entity.Voucher.Type.PERCENTAGE
                    ? new java.math.BigDecimal(amount)
                    : null,
                type == com.ddagtech.aureliabooks.entity.Voucher.Type.FIXED_AMOUNT
                    ? new java.math.BigDecimal(amount)
                    : null,
                cap == null ? null : new java.math.BigDecimal(cap),
                java.math.BigDecimal.ZERO,
                now.minusHours(1),
                now.plusDays(1),
                uses
            ),
            null
        );
        return code;
    }

    private int remaining(String code) {
        return jdbc.queryForObject("SELECT usage_limit FROM vouchers WHERE code=?", Integer.class, code);
    }

    private CheckoutConfirmation prepare(String code) throws Exception {
        String destination = mvc
            .perform(
                post("/checkout/review")
                    .session(session)
                    .with(user(principal))
                    .with(csrf())
                    .param("shippingAddressId", Long.toString(address))
                    .param("paymentMethod", "COD")
                    .param("voucherCode", code)
            )
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
        var result = mvc
            .perform(get(destination).session(session).with(user(principal)))
            .andExpect(status().isOk())
            .andReturn();
        capture("voucher-review.html", result.getResponse().getContentAsString());
        return (CheckoutConfirmation) result.getModelAndView().getModel().get("review");
    }

    private void place(CheckoutConfirmation review, String code) throws Exception {
        mvc.perform(
            post("/checkout/place")
                .session(session)
                .with(user(principal))
                .with(csrf())
                .param("checkoutToken", review.checkoutToken())
                .param("shippingAddressId", Long.toString(address))
                .param("paymentMethod", "COD")
                .param("voucherCode", code)
                .param("finalTotalAmount", "1")
        ).andExpect(redirectedUrl("/orders/" + review.checkoutToken()));
    }

    /** Prepare the review through real HTTP handlers and read the confirmation model instead of fabricating it in the test. */
    private CheckoutConfirmation prepare() throws Exception {
        String destination = mvc
            .perform(
                post("/checkout/review")
                    .session(session)
                    .with(user(principal))
                    .with(csrf())
                    .param("shippingAddressId", Long.toString(address))
                    .param("paymentMethod", "COD")
            )
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
        var result = mvc
            .perform(get(destination).session(session).with(user(principal)))
            .andExpect(status().isOk())
            .andReturn();
        capture("review.html", result.getResponse().getContentAsString());
        return (CheckoutConfirmation) result.getModelAndView().getModel().get("review");
    }

    private void capture(String filename, String html) throws Exception {
        Path directory = Path.of("target/uc11-live-preview");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(filename), html);
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
}
