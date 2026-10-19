package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Full Security -> MVC -> service -> InnoDB flow, without mocking business services. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "checkout.mysql", matches = "true")
class CheckoutFlowMySqlTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    private long owner;
    private long address;
    private long product;
    private long cart;
    private CustomUserDetails principal;
    private MockHttpSession session;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        OrderPlacementMySqlTest.database(registry);
    }
    @AfterAll static void cleanup() throws Exception { OrderPlacementMySqlTest.dropOnlyCreatedQaDatabase(); }

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("uc11_qa_");
        jdbc.update("INSERT INTO roles(role_name) VALUES('ROLE_CUSTOMER') ON DUPLICATE KEY UPDATE role_name=role_name");
        long role = jdbc.queryForObject("SELECT id FROM roles WHERE role_name='ROLE_CUSTOMER'", Long.class);
        String phone = "09" + String.format("%08d", Math.floorMod(UUID.randomUUID().hashCode(), 100000000));
        owner = insert("INSERT INTO users(role_id,password_hash,full_name,phone,phone_verified) VALUES(?,'qa-hash','Nguyễn Minh Anh',?,TRUE)", role, phone);
        long category = insert("INSERT INTO categories(name) VALUES(?)", "QA " + UUID.randomUUID());
        product = insert("""
                INSERT INTO products(barcode,title,category_id,product_type,price,original_cost,stock_quantity)
                VALUES(?,'Đi tìm lẽ sống',?,'BOOK',100000,50000,10)
                """, UUID.randomUUID().toString(), category);
        address = insert("""
                INSERT INTO shipping_addresses(user_id,recipient_name,phone,economic_region,province,district,ward,detailed_address,is_default)
                VALUES(?,'Nguyễn Minh Anh','0912345678','NORTHERN','Hà Nội','Ba Đình','Đội Cấn','12 Đội Cấn',TRUE)
                """, owner);
        cart = insert("INSERT INTO carts(user_id) VALUES(?)", owner);
        jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity) VALUES(?,?,2)", cart, product);
        principal = new CustomUserDetails(users.findById(owner).orElseThrow());
        session = new MockHttpSession();
    }

    @Test void customerCompletesCodCheckoutAndReplayKeepsSingleOrder() throws Exception {
        jdbc.update("UPDATE users SET phone=NULL, phone_verified=FALSE WHERE id=?", owner);
        principal = new CustomUserDetails(users.findById(owner).orElseThrow());
        String html = mvc.perform(get("/cart").session(session).with(user(principal)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/checkout")))
                .andReturn().getResponse().getContentAsString();
        capture("cart.html", html);
        capture("checkout.html", mvc.perform(get("/checkout").session(session).with(user(principal)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        CheckoutConfirmation review = prepare();
        String token = review.checkoutToken();
        mvc.perform(post("/checkout/place").session(session).with(user(principal)).with(csrf())
                        .param("checkoutToken", token).param("shippingAddressId", Long.toString(address))
                        .param("paymentMethod", "COD").param("finalTotalAmount", "1"))
                .andExpect(redirectedUrl("/orders/" + token));
        capture("order-confirm.html", mvc.perform(get("/orders/" + token).session(session).with(user(principal)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Chờ xác nhận")))
                .andExpect(content().string(containsString("Chưa thanh toán")))
                .andReturn().getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, product)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id=?", Integer.class, cart)).isZero();
        mvc.perform(post("/checkout/place").session(session).with(user(principal)).with(csrf())
                        .param("checkoutToken", token).param("shippingAddressId", Long.toString(address)).param("paymentMethod", "COD"))
                .andExpect(redirectedUrl("/orders/" + token));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT final_total_amount FROM orders WHERE order_code=?", java.math.BigDecimal.class, token))
                .isEqualByComparingTo("250000");
        assertThat(jdbc.queryForObject("SELECT shipping_phone FROM orders WHERE order_code=?", String.class, token))
                .isEqualTo("0912345678");
        long other = insert("INSERT INTO users(role_id,password_hash,full_name,phone,phone_verified) SELECT role_id,'qa-hash','Other',NULL,TRUE FROM users WHERE id=?", owner);
        var otherPrincipal = new CustomUserDetails(users.findById(other).orElseThrow());
        mvc.perform(get("/orders/" + token).with(user(otherPrincipal))).andExpect(status().isNotFound());
    }

    @Test void stalePriceRequiresNewReviewBeforeAnyOrderOrStockChange() throws Exception {
        CheckoutConfirmation review = prepare();
        jdbc.update("UPDATE products SET price=110000 WHERE id=?", product);
        mvc.perform(post("/checkout/place").session(session).with(user(principal)).with(csrf())
                        .param("checkoutToken", review.checkoutToken()).param("shippingAddressId", Long.toString(address))
                        .param("paymentMethod", "COD"))
                .andExpect(redirectedUrl("/checkout")).andExpect(flash().attributeExists("errorMessage"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, product)).isEqualTo(10);
        CheckoutConfirmation next = prepare();
        assertThat(next.checkoutToken()).isNotEqualTo(review.checkoutToken());
        assertThat(next.reviewedCheckout().finalTotalAmount()).isEqualByComparingTo("270000");
    }

    @Test void inlineAddressCanBeAddedAndSelectedWithoutLeavingCheckout() throws Exception {
        mvc.perform(post("/checkout/addresses").session(session).with(user(principal)).with(csrf())
                        .param("recipientName", "Người nhận mới").param("phone", "0987654321")
                        .param("economicRegion", "SOUTHERN_MEKONG").param("province", "TP. Hồ Chí Minh")
                        .param("district", "Quận 1").param("ward", "Bến Nghé").param("detailedAddress", "12 Lê Lợi"))
                .andExpect(status().is3xxRedirection());
        long selected = jdbc.queryForObject("SELECT id FROM shipping_addresses WHERE user_id=? AND economic_region='SOUTHERN_MEKONG'", Long.class, owner);
        mvc.perform(get("/checkout").session(session).with(user(principal)).param("shippingAddressId", Long.toString(selected)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("230.000")));
    }

    @Test void unavailableVnpayCannotCreateOrder() throws Exception {
        mvc.perform(post("/checkout/review").session(session).with(user(principal)).with(csrf())
                        .param("shippingAddressId", Long.toString(address)).param("paymentMethod", "VNPAY"))
                .andExpect(redirectedUrl("/checkout"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, owner)).isZero();
    }

    private CheckoutConfirmation prepare() throws Exception {
        String destination = mvc.perform(post("/checkout/review").session(session).with(user(principal)).with(csrf())
                        .param("shippingAddressId", Long.toString(address)).param("paymentMethod", "COD"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        var result = mvc.perform(get(destination).session(session).with(user(principal)))
                .andExpect(status().isOk()).andReturn();
        capture("review.html", result.getResponse().getContentAsString());
        return (CheckoutConfirmation) result.getModelAndView().getModel().get("review");
    }

    private void capture(String filename, String html) throws Exception {
        Path directory = Path.of("target/uc11-live-preview"); Files.createDirectories(directory);
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
