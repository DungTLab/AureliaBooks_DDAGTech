package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.CartQuantityRequest;
import com.ddagtech.aureliabooks.dto.response.CartView;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.CartServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CartServiceImpl service;

    private User customer;
    private Cart cart;
    private Product product;
    private CartItem item;

    @BeforeEach
    void setUp() {
        Role role = new Role();
        role.setRoleName("ROLE_CUSTOMER");

        customer = new User();
        customer.setId(1L);
        customer.setRole(role);
        customer.setIsActive(true);

        cart = new Cart();
        cart.setId(10L);
        cart.setUser(customer);

        product = new Product();
        product.setId(100L);
        product.setTitle("Sách A");
        product.setPrice(new BigDecimal("10000"));
        product.setStockQuantity(20);
        product.setIsActive(true);

        item = new CartItem();
        item.setId(1000L);
        item.setCart(cart);
        item.setProduct(product);
        item.setQuantity(3);
    }

    @Test
    void addingExistingProductIncreasesQuantityWithoutChangingStock() {
        stubExistingItemForAddition();

        service.addItem(1L, 100L, request(2));

        assertEquals(5, item.getQuantity());
        assertEquals(20, product.getStockQuantity());

        verify(cartItemRepository).save(item);
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void addingBeyondStockKeepsPreviousQuantity() {
        product.setStockQuantity(4);
        stubExistingItemForAddition();

        // Đang có 3, thêm 2 thành 5, nhưng tồn chỉ có 4.
        AppException error = assertThrows(
                AppException.class,
                () -> service.addItem(1L, 100L, request(2))
        );

        assertEquals(ErrorCode.INVALID_INPUT_DATA, error.getErrorCode());
        assertTrue(error.getMessage().contains("Không đủ tồn kho"));
        assertEquals(3, item.getQuantity());
        assertEquals(4, product.getStockQuantity());

        verify(cartItemRepository, never()).save(any(CartItem.class));
        verify(cartRepository, never()).save(any(Cart.class));
    }

    @Test
    void resultingQuantityCannotExceed99EvenWhenStockIsEnough() {
        item.setQuantity(95);
        product.setStockQuantity(200);
        stubExistingItemForAddition();

        // Số lượng gửi lên là 5 hợp lệ, nhưng tổng 100 không hợp lệ.
        AppException error = assertThrows(
                AppException.class,
                () -> service.addItem(1L, 100L, request(5))
        );

        assertEquals(ErrorCode.INVALID_INPUT_DATA, error.getErrorCode());
        assertEquals(95, item.getQuantity());

        verify(cartItemRepository, never()).save(any(CartItem.class));
    }

    @Test
    void updatingQuantityReplacesInsteadOfAdding() {
        when(userRepository.findForCartUpdate(1L))
                .thenReturn(Optional.of(customer));

        when(cartItemRepository.findByIdAndCart_User_Id(1000L, 1L))
                .thenReturn(Optional.of(item));

        service.updateQuantity(1L, 1000L, request(2));

        assertEquals(2, item.getQuantity());
        assertEquals(20, product.getStockQuantity());
        verify(cartItemRepository).save(item);
    }

    @Test
    void cannotRemoveItemOutsideCustomersCart() {
        when(userRepository.findForCartUpdate(1L))
                .thenReturn(Optional.of(customer));

        // Không tìm thấy dòng này trong giỏ của người dùng 1.
        when(cartItemRepository.findByIdAndCart_User_Id(2000L, 1L))
                .thenReturn(Optional.empty());

        AppException error = assertThrows(
                AppException.class,
                () -> service.removeItem(1L, 2000L)
        );

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.getErrorCode());

        verify(cartItemRepository, never()).delete(any(CartItem.class));
        verify(cartRepository, never()).save(any(Cart.class));
    }

    @Test
    void viewingCartUsesCurrentPriceAndKeepsUnavailableItem() {
        product.setPrice(new BigDecimal("12000"));
        product.setStockQuantity(2);

        when(userRepository.findById(1L))
                .thenReturn(Optional.of(customer));
        when(cartRepository.findByUser_Id(1L))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCart_Id(10L))
                .thenReturn(List.of(item));

        CartView result = service.viewCart(1L);

        assertEquals(1, result.itemCount());
        assertEquals(3, result.totalQuantity().intValue());

        assertEquals(
                0,
                new BigDecimal("36000")
                        .compareTo(result.merchandiseSubtotal())
        );

        assertNotNull(result.items().get(0).warningMessage());
        assertEquals(3, item.getQuantity());

        verify(cartItemRepository, never()).delete(any(CartItem.class));
        verify(cartItemRepository, never()).save(any(CartItem.class));
    }

    @Test
    void retrievalFailureIsNotReportedAsEmptyCart() {
        when(userRepository.findById(1L))
                .thenReturn(Optional.of(customer));
        when(cartRepository.findByUser_Id(1L))
                .thenThrow(new IllegalStateException("Database unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> service.viewCart(1L)
        );
    }

    private CartQuantityRequest request(int quantity) {
        CartQuantityRequest request = new CartQuantityRequest();
        request.setQuantity(quantity);
        return request;
    }

    private void stubExistingItemForAddition() {
        when(userRepository.findForCartUpdate(1L))
                .thenReturn(Optional.of(customer));
        when(productRepository.findById(100L))
                .thenReturn(Optional.of(product));
        when(cartRepository.findByUser_Id(1L))
                .thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCart_IdAndProduct_Id(10L, 100L))
                .thenReturn(Optional.of(item));
    }
}