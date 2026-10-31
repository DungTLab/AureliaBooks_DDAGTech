package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.mapper.CartMapper;
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
    @org.mockito.Spy
    private CartMapper cartMapper = new CartMapper();


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
        cart.addItem(item);
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
    void resultingQuantityCannotExceed10EvenWhenStockIsEnough() {
        item.setQuantity(8);
        product.setStockQuantity(200);
        stubExistingItemForAddition();

        // The requested increment is valid, but the resulting quantity exceeds the per-product limit.
        AppException error = assertThrows(
                AppException.class,
                () -> service.addItem(1L, 100L, request(5))
        );

        assertEquals(ErrorCode.INVALID_INPUT_DATA, error.getErrorCode());
        assertEquals(8, item.getQuantity());

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

    @Test
    void firstAdditionCreatesPersistentCartAndMaintainsBothSides() {
        when(userRepository.findForCartUpdate(1L)).thenReturn(Optional.of(customer));
        when(productRepository.findById(100L)).thenReturn(Optional.of(product));
        when(cartRepository.findByUser_Id(1L)).thenReturn(Optional.empty());
        when(cartRepository.save(any())).thenAnswer(call -> {
            Cart saved = call.getArgument(0);
            saved.setId(20L);
            return saved;
        });

        service.addItem(1L, 100L, request(2));

        var saved = org.mockito.ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(saved.capture());
        assertEquals(customer, saved.getValue().getCart().getUser());
        assertEquals(2, saved.getValue().getQuantity());
        assertTrue(saved.getValue().getCart().getItems().contains(saved.getValue()));
        assertEquals(20, product.getStockQuantity());
        verify(productRepository, never()).save(any());
    }

    @Test
    void viewingCartWithoutSavedItemsDoesNotCreateCart() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(cartRepository.findByUser_Id(1L)).thenReturn(Optional.empty());

        CartView result = service.viewCart(1L);

        assertTrue(result.isEmpty());
        assertEquals(BigDecimal.ZERO, result.merchandiseSubtotal());
        assertEquals(0, result.totalQuantity());
        verify(cartRepository, never()).save(any());
        verifyNoInteractions(cartItemRepository);
    }

    @Test
    void inactiveProductCannotBeAddedAndPreviousCartRemainsUnchanged() {
        product.setIsActive(false);
        stubExistingItemForAddition();

        assertThrows(AppException.class, () -> service.addItem(1L, 100L, request(2)));

        assertEquals(3, item.getQuantity());
        assertEquals(20, product.getStockQuantity());
        verify(cartRepository, never()).save(any());
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void missingProductCannotCreateCart() {
        when(userRepository.findForCartUpdate(1L)).thenReturn(Optional.of(customer));
        when(productRepository.findById(100L)).thenReturn(Optional.empty());

        assertThrows(AppException.class, () -> service.addItem(1L, 100L, request(1)));

        verifyNoInteractions(cartRepository, cartItemRepository);
    }

    @Test
    void replacementBeyondStockSavesMaximumAvailableQuantity() {
        product.setStockQuantity(6);
        when(userRepository.findForCartUpdate(1L)).thenReturn(Optional.of(customer));
        when(cartItemRepository.findByIdAndCart_User_Id(1000L, 1L)).thenReturn(Optional.of(item));

        service.updateQuantity(1L, 1000L, request(10));

        assertEquals(6, item.getQuantity());
        assertEquals(6, product.getStockQuantity());
        verify(cartItemRepository).save(item);
        verify(cartRepository).save(cart);
        verifyNoInteractions(productRepository);
    }

    @Test
    void replacementUsesLatestStockEvenWhenPreviousQuantityExceedsIt() {
        product.setStockQuantity(2);
        when(userRepository.findForCartUpdate(1L)).thenReturn(Optional.of(customer));
        when(cartItemRepository.findByIdAndCart_User_Id(1000L, 1L)).thenReturn(Optional.of(item));

        service.updateQuantity(1L, 1000L, request(4));

        assertEquals(2, item.getQuantity());
        assertEquals(2, product.getStockQuantity());
        verify(cartItemRepository).save(item);
        verify(cartRepository).save(cart);
    }

    @Test
    void outOfStockReplacementKeepsPreviousQuantity() {
        product.setStockQuantity(0);
        when(userRepository.findForCartUpdate(1L)).thenReturn(Optional.of(customer));
        when(cartItemRepository.findByIdAndCart_User_Id(1000L, 1L)).thenReturn(Optional.of(item));

        assertThrows(AppException.class, () -> service.updateQuantity(1L, 1000L, request(4)));

        assertEquals(3, item.getQuantity());
        assertEquals(0, product.getStockQuantity());
        verify(cartItemRepository, never()).save(any());
        verify(cartRepository, never()).save(any());
    }

    @Test
    void successfulRemovalKeepsStockUnchangedAndDetachesItem() {
        when(userRepository.findForCartUpdate(1L)).thenReturn(Optional.of(customer));
        when(cartItemRepository.findByIdAndCart_User_Id(1000L, 1L)).thenReturn(Optional.of(item));

        service.removeItem(1L, 1000L);

        assertTrue(cart.getItems().isEmpty());
        assertNull(item.getCart());
        assertEquals(20, product.getStockQuantity());
        verify(cartItemRepository).delete(item);
        verify(cartRepository).save(cart);
        verifyNoInteractions(productRepository);
    }

    @Test
    void unavailableItemsAreKeptWithWarningsAndCurrentPrice() {
        product.setIsActive(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(cartRepository.findByUser_Id(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCart_Id(10L)).thenReturn(List.of(item));

        CartView result = service.viewCart(1L);

        assertFalse(result.items().getFirst().availableForSale());
        assertNotNull(result.items().getFirst().warningMessage());
        assertEquals(new BigDecimal("30000"), result.merchandiseSubtotal());
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void badgeUsesOwnedPersistedQuantityWithoutLoadingWholeCart() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(cartItemRepository.sumQuantityByUserId(1L)).thenReturn(5L);

        assertEquals(5L, service.countItems(1L));
        verifyNoInteractions(cartRepository);
        verify(cartItemRepository, never()).findAllByCart_Id(any());
    }

    @Test
    void twentiethProductCanBeAddedButTwentyFirstCannot() {
        cart.getItems().clear();
        when(userRepository.findForCartUpdate(1L)).thenReturn(Optional.of(customer));
        when(productRepository.findById(100L)).thenReturn(Optional.of(product));
        when(productRepository.findById(101L)).thenReturn(Optional.of(Product.builder().id(101L)
                .title("Sách B").price(BigDecimal.ONE).stockQuantity(20).build()));
        when(cartRepository.findByUser_Id(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.countByCart_Id(10L)).thenReturn(19L, 20L);

        service.addItem(1L, 100L, request(1));
        AppException error = assertThrows(AppException.class, () -> service.addItem(1L, 101L, request(1)));

        assertEquals(ErrorCode.CART_LINE_LIMIT_EXCEEDED, error.getErrorCode());
        verify(cartItemRepository, times(1)).save(any());
        assertEquals(20, product.getStockQuantity());
    }

    @Test
    void existingProductCanReach10WithoutUsingAnotherCartSlot() {
        item.setQuantity(9);
        stubExistingItemForAddition();

        service.addItem(1L, 100L, request(1));

        assertEquals(10, item.getQuantity());
        verify(cartItemRepository, never()).countByCart_Id(any());
    }

    @Test
    void updatingQuantityTo11IsRejectedWithoutSaving() {
        assertThrows(AppException.class, () -> service.updateQuantity(1L, 1000L, request(11)));
        assertEquals(3, item.getQuantity());
        verify(cartItemRepository, never()).save(any());
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
