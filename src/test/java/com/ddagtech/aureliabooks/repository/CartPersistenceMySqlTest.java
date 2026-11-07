package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.request.CartQuantityRequest;
import com.ddagtech.aureliabooks.entity.Cart;
import com.ddagtech.aureliabooks.entity.CartItem;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.service.impl.CartServiceImpl;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: initialize a disposable uc10_qa_ database with docker/initdb/01_schema.sql. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CartServiceImpl.class, com.ddagtech.aureliabooks.mapper.CartMapper.class})
@EnabledIfSystemProperty(named = "cart.mysql", matches = "true")
class CartPersistenceMySqlTest {

    @Autowired TestEntityManager entities;
    @Autowired CartRepository carts;
    @Autowired CartItemRepository items;
    @Autowired CartServiceImpl service;
    @Autowired JdbcTemplate jdbc;

    private User customer;
    private Product product;

    @BeforeEach
    void setUp() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(database);
        assertTrue(database.startsWith("uc10_qa_"), "Only a disposable cart QA database is allowed");

        Role role = entities.persist(Role.builder().roleName("ROLE_CUSTOMER").build());
        customer = entities.persist(User.builder().role(role).email("cart-qa@example.com")
                .passwordHash("qa-password-hash").fullName("Cart QA").build());
        Category category = entities.persist(Category.builder().name("Cart QA").build());
        product = entities.persist(Product.builder().barcode("cart-qa-product").title("Cart QA Product")
                .category(category).productType(Product.ProductType.BOOK)
                .price(new BigDecimal("10000.00")).originalCost(new BigDecimal("5000.00"))
                .stockQuantity(20).build());
        entities.flush();
    }

    @Test
    void cartAndItemsRoundTripWithSchemaTimestampsAndDefaultQuantity() {
        Cart cart = persistCart();
        entities.clear();

        Cart storedCart = carts.findByUser_Id(customer.getId()).orElseThrow();
        CartItem storedItem = items.findByCart_IdAndProduct_Id(cart.getId(), product.getId()).orElseThrow();
        assertNotNull(storedCart.getCreatedAt());
        assertNotNull(storedCart.getUpdatedAt());
        assertNotNull(storedItem.getCreatedAt());
        assertEquals(1, storedItem.getQuantity());
        assertEquals(storedCart, storedItem.getCart());
        assertEquals(1, storedCart.getItems().size());
    }

    @Test
    void removingItemFromCartDeletesOrphanAndPreservesProduct() {
        Cart cart = persistCart();
        CartItem item = cart.getItems().getFirst();
        Long itemId = item.getId();

        cart.removeItem(item);
        entities.flush();
        entities.clear();

        assertFalse(items.existsById(itemId));
        assertNotNull(entities.find(Product.class, product.getId()));
        assertTrue(carts.findById(cart.getId()).orElseThrow().getItems().isEmpty());
    }

    @Test
    void deletingCartDeletesItemsAndPreservesUserAndProduct() {
        Cart cart = persistCart();
        Long itemId = cart.getItems().getFirst().getId();
        carts.delete(cart);
        entities.flush();
        entities.clear();

        assertFalse(carts.existsById(cart.getId()));
        assertFalse(items.existsById(itemId));
        assertNotNull(entities.find(User.class, customer.getId()));
        assertNotNull(entities.find(Product.class, product.getId()));
    }

    @Test
    void databaseRejectsSecondCartForSameUser() {
        persistCart();
        Cart duplicate = new Cart();
        duplicate.setUser(customer);

        assertThrows(DataIntegrityViolationException.class, () -> carts.saveAndFlush(duplicate));
    }

    @Test
    void databaseRejectsDuplicateProductInSameCart() {
        Cart cart = persistCart();
        CartItem duplicate = new CartItem();
        duplicate.setCart(cart);
        duplicate.setProduct(product);

        assertThrows(DataIntegrityViolationException.class, () -> items.saveAndFlush(duplicate));
    }

    @Test
    void invalidQuantityIsRejectedBeforePersistence() {
        Cart cart = persistCart();
        cart.getItems().getFirst().setQuantity(0);

        assertThrows(ConstraintViolationException.class, entities::flush);
    }

    @Test
    void databaseRestrictsDeletingProductReferencedByCartItem() {
        persistCart();

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM products WHERE id = ?", product.getId()));
    }

    @Test
    void serviceAddsUpdatesAndRemovesItemsWithCascadeMapping() {
        CartQuantityRequest request = new CartQuantityRequest();
        request.setQuantity(2);
        service.addItem(customer.getId(), product.getId(), request);
        entities.flush();
        entities.clear();

        Cart cart = carts.findByUser_Id(customer.getId()).orElseThrow();
        CartItem item = cart.getItems().getFirst();
        request.setQuantity(3);
        service.updateQuantity(customer.getId(), item.getId(), request);
        entities.flush();
        assertEquals(3, service.viewCart(customer.getId()).totalQuantity().intValue());

        service.removeItem(customer.getId(), item.getId());
        entities.flush();
        entities.clear();
        assertTrue(service.viewCart(customer.getId()).items().isEmpty());
        assertEquals(20, entities.find(Product.class, product.getId()).getStockQuantity());
    }

    private Cart persistCart() {
        Cart cart = new Cart();
        cart.setUser(customer);
        CartItem item = new CartItem();
        item.setProduct(product);
        cart.addItem(item);
        return entities.persistAndFlush(cart);
    }
}
