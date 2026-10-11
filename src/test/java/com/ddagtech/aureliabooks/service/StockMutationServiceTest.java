package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.GoodsReceipt;
import com.ddagtech.aureliabooks.entity.GoodsReceiptItem;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.OrderItem;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.GoodsReceiptItemRepository;
import com.ddagtech.aureliabooks.repository.GoodsReceiptRepository;
import com.ddagtech.aureliabooks.repository.OrderItemRepository;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.StockMutationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FND-03 Test Suite: Document-based Stock Posting & Transaction Consistency.
 * Verifies atomic multi-line mutation, insufficient stock rollback,
 * posting metadata stamping, retry idempotency, and independent return stock restore.
 */
@ExtendWith(MockitoExtension.class)
class StockMutationServiceTest {

    @Mock
    private GoodsReceiptRepository goodsReceiptRepository;

    @Mock
    private GoodsReceiptItemRepository goodsReceiptItemRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private StockMutationServiceImpl stockMutationService;

    private User testManager;
    private User testStaff;
    private User testCustomer;
    private Product product1;
    private Product product2;

    @BeforeEach
    void setUp() {
        testManager = User.builder()
                .id(1L)
                .email("manager@aureliabooks.com")
                .fullName("Quản lý kho")
                .build();

        testStaff = User.builder()
                .id(2L)
                .email("staff@aureliabooks.com")
                .fullName("Nhân viên kho")
                .build();

        testCustomer = User.builder()
                .id(10L)
                .email("customer@example.com")
                .fullName("Khách hàng A")
                .build();

        product1 = Product.builder()
                .id(101L)
                .barcode("893000000001")
                .title("Đắc Nhân Tâm")
                .stockQuantity(10)
                .price(new BigDecimal("100000.00"))
                .originalCost(new BigDecimal("60000.00"))
                .build();

        product2 = Product.builder()
                .id(102L)
                .barcode("893000000002")
                .title("Nhà Giả Kim")
                .stockQuantity(5)
                .price(new BigDecimal("90000.00"))
                .originalCost(new BigDecimal("50000.00"))
                .build();
    }

    // =========================================================================
    // 1. RECEIVE GOODS RECEIPT (Nhập kho theo chứng từ)
    // =========================================================================
    @Nested
    @DisplayName("receiveReceipt - Document-based Goods Inbound Posting")
    class ReceiveReceiptTests {

        @Test
        @DisplayName("Atomic multi-line receipt posting updates stock and stamps metadata")
        void shouldAtomicallyPostReceiptLinesAndStampMetadata() {
            // Arrange
            Long receiptId = 1L;
            GoodsReceipt receipt = GoodsReceipt.builder()
                    .id(receiptId)
                    .receiptCode("GR-2026-0001")
                    .status(GoodsReceipt.Status.DRAFT)
                    .build();

            GoodsReceiptItem item1 = GoodsReceiptItem.builder()
                    .id(1L)
                    .receipt(receipt)
                    .product(product1)
                    .receivedQuantity(20)
                    .unitCost(new BigDecimal("50000.00"))
                    .build();

            GoodsReceiptItem item2 = GoodsReceiptItem.builder()
                    .id(2L)
                    .receipt(receipt)
                    .product(product2)
                    .receivedQuantity(15)
                    .unitCost(new BigDecimal("45000.00"))
                    .build();

            when(userRepository.findById(testManager.getId())).thenReturn(Optional.of(testManager));
            when(goodsReceiptRepository.findByIdWithLock(receiptId)).thenReturn(Optional.of(receipt));
            when(goodsReceiptItemRepository.findByReceiptId(receiptId)).thenReturn(Arrays.asList(item1, item2));
            when(productRepository.findAllByIdInWithLock(anyCollection())).thenReturn(Arrays.asList(product1, product2));

            // Act
            stockMutationService.receiveReceipt(receiptId, testManager.getId());

            // Assert
            assertThat(product1.getStockQuantity()).isEqualTo(10 + 20); // 30
            assertThat(product2.getStockQuantity()).isEqualTo(5 + 15);  // 20
            assertThat(receipt.getStatus()).isEqualTo(GoodsReceipt.Status.RECEIVED);
            assertThat(receipt.getReceivedAt()).isNotNull();
            assertThat(receipt.getReceivedBy()).isEqualTo(testManager);

            verify(goodsReceiptRepository).save(receipt);
            verify(auditLogService).record(eq(testManager.getId()), eq("RECEIVE_GOODS"), eq("goods_receipts"), eq(receiptId), anyString(), isNull());
        }

        @Test
        @DisplayName("Replay protection: retry receiveReceipt does not increment stock twice")
        void shouldIgnoreReplayWhenReceiptAlreadyReceived() {
            // Arrange
            Long receiptId = 1L;
            GoodsReceipt receipt = GoodsReceipt.builder()
                    .id(receiptId)
                    .receiptCode("GR-2026-0001")
                    .status(GoodsReceipt.Status.RECEIVED)
                    .receivedAt(LocalDateTime.now().minusHours(1))
                    .receivedBy(testManager)
                    .build();

            when(userRepository.findById(testManager.getId())).thenReturn(Optional.of(testManager));
            when(goodsReceiptRepository.findByIdWithLock(receiptId)).thenReturn(Optional.of(receipt));

            // Act
            stockMutationService.receiveReceipt(receiptId, testManager.getId());

            // Assert
            assertThat(product1.getStockQuantity()).isEqualTo(10); // Unchanged
            verify(goodsReceiptItemRepository, never()).findByReceiptId(any());
            verify(productRepository, never()).save(any());
            verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Reject posting when receipt has no items")
        void shouldRejectPostingEmptyReceipt() {
            // Arrange
            Long receiptId = 1L;
            GoodsReceipt receipt = GoodsReceipt.builder()
                    .id(receiptId)
                    .receiptCode("GR-2026-0001")
                    .status(GoodsReceipt.Status.DRAFT)
                    .build();

            when(userRepository.findById(testManager.getId())).thenReturn(Optional.of(testManager));
            when(goodsReceiptRepository.findByIdWithLock(receiptId)).thenReturn(Optional.of(receipt));
            when(goodsReceiptItemRepository.findByReceiptId(receiptId)).thenReturn(Collections.emptyList());

            // Act & Assert
            assertThatThrownBy(() -> stockMutationService.receiveReceipt(receiptId, testManager.getId()))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("contains no line items");

            assertThat(receipt.getStatus()).isEqualTo(GoodsReceipt.Status.DRAFT);
            assertThat(receipt.getReceivedAt()).isNull();
        }
    }

    // =========================================================================
    // 2. DEDUCT ORDER (Trừ kho theo đơn hàng)
    // =========================================================================
    @Nested
    @DisplayName("deductOrder - Document-based Order Stock Deduction")
    class DeductOrderTests {

        @Test
        @DisplayName("Atomic multi-line deduction: all items deducted and stock_deducted_at stamped")
        void shouldAtomicallyDeductAllOrderItemsAndStampMetadata() {
            // Arrange
            Long orderId = 100L;
            Order order = Order.builder()
                    .id(orderId)
                    .orderCode("ORD-2026-0001")
                    .user(testCustomer)
                    .orderStatus(Order.OrderStatus.CONFIRMED)
                    .build();

            OrderItem item1 = OrderItem.builder()
                    .id(1L)
                    .order(order)
                    .product(product1)
                    .quantity(3)
                    .build();

            OrderItem item2 = OrderItem.builder()
                    .id(2L)
                    .order(order)
                    .product(product2)
                    .quantity(2)
                    .build();

            when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
            when(orderItemRepository.findByOrderId(orderId)).thenReturn(Arrays.asList(item1, item2));
            when(productRepository.findAllByIdInWithLock(anyCollection())).thenReturn(Arrays.asList(product1, product2));

            // Act
            stockMutationService.deductOrder(orderId);

            // Assert
            assertThat(product1.getStockQuantity()).isEqualTo(10 - 3); // 7
            assertThat(product2.getStockQuantity()).isEqualTo(5 - 2);  // 3
            assertThat(order.getStockDeductedAt()).isNotNull();

            verify(orderRepository).save(order);
            verify(auditLogService).record(eq(testCustomer.getId()), eq("ORDER_DEDUCT_STOCK"), eq("orders"), eq(orderId), anyString(), isNull());
        }

        @Test
        @DisplayName("Rollback on insufficient stock: if one line is insufficient, NO lines are deducted")
        void shouldRollbackAndThrowExceptionWhenAnyProductHasInsufficientStock() {
            // Arrange: product1 has 10 (needs 3), product2 has 5 (needs 10 -> INSUFFICIENT)
            Long orderId = 100L;
            Order order = Order.builder()
                    .id(orderId)
                    .orderCode("ORD-2026-0001")
                    .user(testCustomer)
                    .orderStatus(Order.OrderStatus.CONFIRMED)
                    .build();

            OrderItem item1 = OrderItem.builder()
                    .id(1L)
                    .order(order)
                    .product(product1)
                    .quantity(3)
                    .build();

            OrderItem item2 = OrderItem.builder()
                    .id(2L)
                    .order(order)
                    .product(product2)
                    .quantity(10) // Exceeds product2.stockQuantity (5)
                    .build();

            when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
            when(orderItemRepository.findByOrderId(orderId)).thenReturn(Arrays.asList(item1, item2));
            when(productRepository.findAllByIdInWithLock(anyCollection())).thenReturn(Arrays.asList(product1, product2));

            // Act & Assert
            assertThatThrownBy(() -> stockMutationService.deductOrder(orderId))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_STOCK);

            // Product1 must NOT be decremented! Atomicity guaranteed!
            assertThat(product1.getStockQuantity()).isEqualTo(10);
            assertThat(product2.getStockQuantity()).isEqualTo(5);
            assertThat(order.getStockDeductedAt()).isNull();

            verify(orderRepository, never()).save(order);
            verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Replay protection: retry deductOrder does not deduct stock a second time")
        void shouldIgnoreReplayWhenOrderAlreadyDeducted() {
            // Arrange
            Long orderId = 100L;
            LocalDateTime pastDeductionTime = LocalDateTime.now().minusMinutes(30);
            Order order = Order.builder()
                    .id(orderId)
                    .orderCode("ORD-2026-0001")
                    .user(testCustomer)
                    .orderStatus(Order.OrderStatus.CONFIRMED)
                    .stockDeductedAt(pastDeductionTime)
                    .build();

            when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));

            // Act
            stockMutationService.deductOrder(orderId);

            // Assert
            assertThat(product1.getStockQuantity()).isEqualTo(10); // Unchanged
            assertThat(order.getStockDeductedAt()).isEqualTo(pastDeductionTime);
            verify(orderItemRepository, never()).findByOrderId(any());
            verify(productRepository, never()).save(any());
            verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Reject deduction for cancelled order")
        void shouldRejectDeductionForCancelledOrder() {
            // Arrange
            Long orderId = 100L;
            Order order = Order.builder()
                    .id(orderId)
                    .orderCode("ORD-2026-0001")
                    .user(testCustomer)
                    .orderStatus(Order.OrderStatus.CANCELLED)
                    .build();

            when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));

            // Act & Assert
            assertThatThrownBy(() -> stockMutationService.deductOrder(orderId))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);
        }
    }

    // =========================================================================
    // 3. RESTORE CANCELLED ORDER (Hoàn tồn đơn hủy)
    // =========================================================================
    @Nested
    @DisplayName("restoreCancelledOrder - Cancelled Order Inventory Restoration")
    class RestoreCancelledOrderTests {

        @Test
        @DisplayName("Cancellation restores stock only when stock was previously deducted")
        void shouldRestoreStockForOrderThatHadStockDeducted() {
            // Arrange
            Long orderId = 200L;
            LocalDateTime deductedAt = LocalDateTime.now().minusHours(2);
            Order order = Order.builder()
                    .id(orderId)
                    .orderCode("ORD-2026-0002")
                    .user(testCustomer)
                    .orderStatus(Order.OrderStatus.CONFIRMED)
                    .stockDeductedAt(deductedAt)
                    .build();

            OrderItem item1 = OrderItem.builder()
                    .id(1L)
                    .order(order)
                    .product(product1)
                    .quantity(4)
                    .build();

            when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
            when(userRepository.findById(testCustomer.getId())).thenReturn(Optional.of(testCustomer));
            when(orderItemRepository.findByOrderId(orderId)).thenReturn(Collections.singletonList(item1));
            when(productRepository.findAllByIdInWithLock(anyCollection())).thenReturn(Collections.singletonList(product1));

            // Act
            stockMutationService.restoreCancelledOrder(orderId, testCustomer.getId());

            // Assert
            assertThat(product1.getStockQuantity()).isEqualTo(10 + 4); // Restored
            assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
            assertThat(order.getStockRestoredAt()).isNotNull();
            assertThat(order.getStockRestoreReason()).isEqualTo(Order.StockRestoreReason.CANCELLED);
            assertThat(order.getStockRestoredBy()).isEqualTo(testCustomer);

            verify(orderRepository).save(order);
            verify(auditLogService).record(eq(testCustomer.getId()), eq("ORDER_CANCELLED_RESTOCK"), eq("orders"), eq(orderId), anyString(), isNull());
        }

        @Test
        @DisplayName("Order cancelled before deduction: does NOT restore stock and does NOT stamp stockRestoredAt")
        void shouldNotRestoreStockIfOrderWasNeverDeducted() {
            // Arrange: order never had stock deducted (stockDeductedAt == null)
            Long orderId = 200L;
            Order order = Order.builder()
                    .id(orderId)
                    .orderCode("ORD-2026-0002")
                    .user(testCustomer)
                    .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                    .stockDeductedAt(null)
                    .build();

            when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));

            // Act
            stockMutationService.restoreCancelledOrder(orderId, testCustomer.getId());

            // Assert: No stock change, no stockRestoredAt timestamp stamped (preserves DB check constraint)
            assertThat(product1.getStockQuantity()).isEqualTo(10);
            assertThat(order.getOrderStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
            assertThat(order.getStockRestoredAt()).isNull();
            assertThat(order.getStockRestoreReason()).isNull();

            verify(orderItemRepository, never()).findByOrderId(any());
            verify(productRepository, never()).save(any());
            verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Replay protection: retry restoreCancelledOrder does not restore stock twice")
        void shouldIgnoreReplayWhenOrderAlreadyRestored() {
            // Arrange
            Long orderId = 200L;
            LocalDateTime restoredAt = LocalDateTime.now().minusMinutes(10);
            Order order = Order.builder()
                    .id(orderId)
                    .orderCode("ORD-2026-0002")
                    .user(testCustomer)
                    .orderStatus(Order.OrderStatus.CANCELLED)
                    .stockDeductedAt(LocalDateTime.now().minusHours(1))
                    .stockRestoredAt(restoredAt)
                    .stockRestoreReason(Order.StockRestoreReason.CANCELLED)
                    .build();

            when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));

            // Act
            stockMutationService.restoreCancelledOrder(orderId, testCustomer.getId());

            // Assert
            assertThat(product1.getStockQuantity()).isEqualTo(10); // Unchanged
            assertThat(order.getStockRestoredAt()).isEqualTo(restoredAt);
            verify(orderItemRepository, never()).findByOrderId(any());
            verify(productRepository, never()).save(any());
        }
    }

    // =========================================================================
    // 4. RESTORE RETURNED ORDER (Hoàn tồn trả hàng thành công)
    // =========================================================================
    @Nested
    @DisplayName("restoreReturnedOrder - Staff Confirmed Full Return Restoration")
    class RestoreReturnedOrderTests {

        @Test
        @DisplayName("Staff confirms return: restores stock exactly once, independent of payment status (PAID/UNPAID/REFUNDED)")
        void shouldRestoreStockOnReturnIndependentlyOfPaymentRefund() {
            // Arrange: Order is delivered and paymentStatus is REFUNDED
            Long orderId = 300L;
            Order order = Order.builder()
                    .id(orderId)
                    .orderCode("ORD-2026-0003")
                    .user(testCustomer)
                    .orderStatus(Order.OrderStatus.DELIVERED)
                    .paymentStatus(Order.PaymentStatus.REFUNDED) // Payment refund happened separately
                    .stockDeductedAt(LocalDateTime.now().minusDays(2))
                    .build();

            OrderItem item = OrderItem.builder()
                    .id(1L)
                    .order(order)
                    .product(product1)
                    .quantity(5)
                    .build();

            when(userRepository.findById(testStaff.getId())).thenReturn(Optional.of(testStaff));
            when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
            when(orderItemRepository.findByOrderId(orderId)).thenReturn(Collections.singletonList(item));
            when(productRepository.findAllByIdInWithLock(anyCollection())).thenReturn(Collections.singletonList(product1));

            // Act
            stockMutationService.restoreReturnedOrder(orderId, testStaff.getId());

            // Assert
            assertThat(product1.getStockQuantity()).isEqualTo(10 + 5); // 15
            assertThat(order.getStockRestoredAt()).isNotNull();
            assertThat(order.getStockRestoreReason()).isEqualTo(Order.StockRestoreReason.RETURNED);
            assertThat(order.getStockRestoredBy()).isEqualTo(testStaff);

            verify(orderRepository).save(order);
            verify(auditLogService).record(eq(testStaff.getId()), eq("ORDER_RETURN_CONFIRMED"), eq("orders"), eq(orderId), anyString(), isNull());
        }

        @Test
        @DisplayName("Reject return restore if order was never deducted")
        void shouldRejectReturnRestorationIfOrderNeverDeducted() {
            // Arrange
            Long orderId = 300L;
            Order order = Order.builder()
                    .id(orderId)
                    .orderCode("ORD-2026-0003")
                    .user(testCustomer)
                    .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                    .stockDeductedAt(null)
                    .build();

            when(userRepository.findById(testStaff.getId())).thenReturn(Optional.of(testStaff));
            when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));

            // Act & Assert
            assertThatThrownBy(() -> stockMutationService.restoreReturnedOrder(orderId, testStaff.getId()))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_STOCK_NOT_DEDUCTED);

            assertThat(product1.getStockQuantity()).isEqualTo(10);
            assertThat(order.getStockRestoredAt()).isNull();
        }

        @Test
        @DisplayName("Reject return restore when staff ID is null (enforcing actor presence)")
        void shouldRequireStaffIdForReturnConfirmation() {
            // Act & Assert
            assertThatThrownBy(() -> stockMutationService.restoreReturnedOrder(300L, null))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STAFF_REQUIRED_FOR_RETURN);
        }

        @Test
        @DisplayName("Replay protection: retry restoreReturnedOrder does not restore stock twice")
        void shouldIgnoreReplayWhenReturnAlreadyRestored() {
            // Arrange
            Long orderId = 300L;
            LocalDateTime restoredAt = LocalDateTime.now().minusHours(1);
            Order order = Order.builder()
                    .id(orderId)
                    .orderCode("ORD-2026-0003")
                    .user(testCustomer)
                    .orderStatus(Order.OrderStatus.DELIVERED)
                    .stockDeductedAt(LocalDateTime.now().minusDays(1))
                    .stockRestoredAt(restoredAt)
                    .stockRestoreReason(Order.StockRestoreReason.RETURNED)
                    .stockRestoredBy(testStaff)
                    .build();

            when(userRepository.findById(testStaff.getId())).thenReturn(Optional.of(testStaff));
            when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));

            // Act
            stockMutationService.restoreReturnedOrder(orderId, testStaff.getId());

            // Assert
            assertThat(product1.getStockQuantity()).isEqualTo(10); // Unchanged
            assertThat(order.getStockRestoredAt()).isEqualTo(restoredAt);
            verify(orderItemRepository, never()).findByOrderId(any());
            verify(productRepository, never()).save(any());
            verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
        }
    }
}
