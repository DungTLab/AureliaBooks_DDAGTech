package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.StockMovementLogResponse;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.StockMovementLog;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.StockMovementLogRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.StockLedgerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for {@link StockLedgerService} (FND-03).
 * Verifies DoD:
 * 1. current_stock = previous_stock + quantity_change.
 * 2. Strict prevention of negative inventory & rollback on insufficient stock.
 * 3. Immutable append-only ledger (zero UPDATE/DELETE capability).
 * 4. Correct execution for all 4 transaction types: IMPORT, ORDER_DEDUCT, ORDER_CANCELLED_RESTOCK, MANUAL_ADJUSTMENT.
 */
@ExtendWith(MockitoExtension.class)
class StockLedgerServiceTest {

    @Mock
    private StockMovementLogRepository stockMovementLogRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private StockLedgerServiceImpl stockLedgerService;

    private Product sampleProduct;
    private User sampleUser;

    @BeforeEach
    void setUp() {
        Category category = Category.builder().name("Văn Học").build();
        category.setId(1L);

        sampleProduct = Product.builder()
                .id(100L)
                .barcode("8935212345678")
                .title("Đắc Nhân Tâm")
                .productType(Product.ProductType.BOOK)
                .price(BigDecimal.valueOf(86000))
                .originalCost(BigDecimal.valueOf(50000))
                .stockQuantity(50)
                .isActive(true)
                .category(category)
                .build();

        sampleUser = User.builder()
                .id(1L)
                .email("ducanh@aureliabook.vn")
                .fullName("Nguyễn Trần Đức Anh")
                .build();
    }

    @Test
    @DisplayName("DoD 1: recordImport should increase stock and satisfy current_stock = previous_stock + quantity_change")
    void testRecordImport_IncreasesStockAndSatisfiesInvariant() {
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(stockMovementLogRepository.save(any(StockMovementLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockMovementLog result = stockLedgerService.recordImport(100L, 20, "GRN-202610-001", 1L, "Nhập bổ sung đợt 1");

        assertThat(result).isNotNull();
        assertThat(result.getTransactionType()).isEqualTo(StockMovementLog.TransactionType.IMPORT);
        assertThat(result.getPreviousStock()).isEqualTo(50);
        assertThat(result.getQuantityChange()).isEqualTo(20);
        assertThat(result.getCurrentStock()).isEqualTo(70);
        assertThat(result.getCurrentStock()).isEqualTo(result.getPreviousStock() + result.getQuantityChange());
        assertThat(result.getReferenceCode()).isEqualTo("GRN-202610-001");
        assertThat(sampleProduct.getStockQuantity()).isEqualTo(70);

        verify(productRepository).save(sampleProduct);
        verify(stockMovementLogRepository).save(any(StockMovementLog.class));
    }

    @Test
    @DisplayName("DoD 1: recordOrderDeduct should decrease stock and satisfy current_stock = previous_stock + quantity_change")
    void testRecordOrderDeduct_DecreasesStockAndSatisfiesInvariant() {
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(stockMovementLogRepository.save(any(StockMovementLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockMovementLog result = stockLedgerService.recordOrderDeduct(100L, 15, "ORD-202610-999", 1L, "Xuất giao hàng COD");

        assertThat(result).isNotNull();
        assertThat(result.getTransactionType()).isEqualTo(StockMovementLog.TransactionType.ORDER_DEDUCT);
        assertThat(result.getPreviousStock()).isEqualTo(50);
        assertThat(result.getQuantityChange()).isEqualTo(-15);
        assertThat(result.getCurrentStock()).isEqualTo(35);
        assertThat(result.getCurrentStock()).isEqualTo(result.getPreviousStock() + result.getQuantityChange());
        assertThat(result.getReferenceCode()).isEqualTo("ORD-202610-999");
        assertThat(sampleProduct.getStockQuantity()).isEqualTo(35);

        verify(productRepository).save(sampleProduct);
        verify(stockMovementLogRepository).save(any(StockMovementLog.class));
    }

    @Test
    @DisplayName("DoD 2: recordOrderDeduct should fail and rollback when insufficient stock (prevents negative stock)")
    void testRecordOrderDeduct_InsufficientStock_ThrowsAppException() {
        sampleProduct.setStockQuantity(5);
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));

        assertThatThrownBy(() -> stockLedgerService.recordOrderDeduct(100L, 10, "ORD-FAIL", 1L, "Thiếu tồn"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Không đủ tồn kho")
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);

        assertThat(sampleProduct.getStockQuantity()).isEqualTo(5);
        verify(productRepository, never()).save(sampleProduct);
        verify(stockMovementLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("DoD 1 & 4: recordOrderCancelledRestock should increase stock upon order cancellation")
    void testRecordOrderCancelledRestock_IncreasesStock() {
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));
        when(stockMovementLogRepository.save(any(StockMovementLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockMovementLog result = stockLedgerService.recordOrderCancelledRestock(100L, 3, "ORD-CANCEL-123", null, "Hoàn kho hủy đơn trước khi giao");

        assertThat(result).isNotNull();
        assertThat(result.getTransactionType()).isEqualTo(StockMovementLog.TransactionType.ORDER_CANCELLED_RESTOCK);
        assertThat(result.getPreviousStock()).isEqualTo(50);
        assertThat(result.getQuantityChange()).isEqualTo(3);
        assertThat(result.getCurrentStock()).isEqualTo(53);
        assertThat(sampleProduct.getStockQuantity()).isEqualTo(53);
    }

    @Test
    @DisplayName("DoD 4: recordManualAdjustment should handle positive and negative adjustments safely")
    void testRecordManualAdjustment_PositiveAndNegative() {
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));
        when(stockMovementLogRepository.save(any(StockMovementLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 1. Positive adjustment (dư kho kiểm kê)
        StockMovementLog plusResult = stockLedgerService.recordManualAdjustment(100L, 5, "ADJ-2026-01", 1L, "Kiểm kê phát hiện thừa");
        assertThat(plusResult.getCurrentStock()).isEqualTo(55);

        // 2. Negative adjustment (hao hụt hư hỏng)
        StockMovementLog minusResult = stockLedgerService.recordManualAdjustment(100L, -10, "ADJ-2026-02", 1L, "Hàng ẩm mốc rách bìa");
        assertThat(minusResult.getCurrentStock()).isEqualTo(45);
    }

    @Test
    @DisplayName("DoD 2: recordManualAdjustment should reject negative stock result")
    void testRecordManualAdjustment_NegativeResult_ThrowsException() {
        sampleProduct.setStockQuantity(3);
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));

        assertThatThrownBy(() -> stockLedgerService.recordManualAdjustment(100L, -5, "ADJ-FAIL", 1L, "Hao hụt vượt tồn"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    @DisplayName("DoD 1: append should reject mismatched balance invariant (current != previous + change)")
    void testAppend_MismatchedBalance_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.append(
                100L, StockMovementLog.TransactionType.IMPORT, 10, 50, 999, "REF", 1L, "Sai tồn cuối"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_LEDGER_BALANCE_MISMATCH);
    }

    @Test
    @DisplayName("DoD 1: append should reject zero quantity change")
    void testAppend_ZeroQuantityChange_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.append(
                100L, StockMovementLog.TransactionType.IMPORT, 0, 50, 50, "REF", 1L, "Biến động bằng 0"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUANTITY_CHANGE);
    }

    @Test
    @DisplayName("DoD 1: append should reject negative stock balances")
    void testAppend_NegativeStock_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.append(
                100L, StockMovementLog.TransactionType.ORDER_DEDUCT, -10, 5, -5, "REF", 1L, "Tồn âm"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NEGATIVE_STOCK_NOT_ALLOWED);
    }

    @Test
    @DisplayName("DoD 3: StockMovementLog entity must throw UnsupportedOperationException on preUpdate and preRemove")
    void testStockMovementLog_Immutability_ThrowsUnsupportedOperationException() {
        StockMovementLog log = StockMovementLog.builder().build();

        assertThatThrownBy(log::preUpdate)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("cannot be updated");

        assertThatThrownBy(log::preRemove)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    @DisplayName("DoD 3: StockMovementLogRepository must not declare any delete methods")
    void testStockMovementLogRepository_HasNoDeleteMethods() {
        Method[] methods = StockMovementLogRepository.class.getMethods();
        for (Method method : methods) {
            assertThat(method.getName().toLowerCase())
                    .as("Repository must not expose delete operations: " + method.getName())
                    .doesNotStartWith("delete")
                    .doesNotStartWith("remove");
        }
    }

    @Test
    @DisplayName("Query: getLedgerLogsWithFilter should return paginated StockMovementLogResponse")
    void testGetLedgerLogsWithFilter() {
        StockMovementLog log1 = StockMovementLog.builder()
                .id(1L)
                .product(sampleProduct)
                .transactionType(StockMovementLog.TransactionType.IMPORT)
                .quantityChange(50)
                .previousStock(0)
                .currentStock(50)
                .referenceCode("GRN-01")
                .performedBy(sampleUser)
                .createdAt(LocalDateTime.now())
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        Page<StockMovementLog> mockPage = new PageImpl<>(List.of(log1), pageable, 1);

        when(stockMovementLogRepository.findWithFilters(any(), any(), any(), any(), any()))
                .thenReturn(mockPage);

        Page<StockMovementLogResponse> result = stockLedgerService.getLedgerLogsWithFilter(
                StockMovementLog.TransactionType.IMPORT, 100L, null, null, pageable);

        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getReferenceCode()).isEqualTo("GRN-01");
        assertThat(result.getContent().get(0).getPerformedByUserName()).isEqualTo("Nguyễn Trần Đức Anh");
    }
}
