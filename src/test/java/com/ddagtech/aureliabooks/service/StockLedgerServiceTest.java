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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive Unit Test Suite for {@link StockLedgerService} (FND-03).
 * Exhaustively tests all DoD items, boundary values, error scenarios, and invariants:
 * 1. current_stock = previous_stock + quantity_change.
 * 2. Strict prevention of negative inventory & rollback on insufficient stock.
 * 3. Immutable append-only ledger (zero UPDATE/DELETE capability).
 * 4. Correct execution for all 4 transaction types: IMPORT, ORDER_DEDUCT, ORDER_CANCELLED_RESTOCK, MANUAL_ADJUSTMENT.
 * 5. Boundary testing (exact deduction to 0, overflow guards, inactive products, null safety).
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

    // ==========================================
    // 1. RECORD IMPORT (Nhập Kho)
    // ==========================================

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
    @DisplayName("recordImport should default null initial stockQuantity to 0")
    void testRecordImport_NullStockQuantityInProduct_DefaultsToZero() {
        sampleProduct.setStockQuantity(null);
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));
        when(stockMovementLogRepository.save(any(StockMovementLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockMovementLog result = stockLedgerService.recordImport(100L, 10, "GRN-INITIAL", null, "Nhập lần đầu");

        assertThat(result.getPreviousStock()).isEqualTo(0);
        assertThat(result.getCurrentStock()).isEqualTo(10);
        assertThat(sampleProduct.getStockQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("recordImport should reject non-positive quantities (0 or negative)")
    void testRecordImport_NonPositiveQuantity_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.recordImport(100L, 0, "GRN-0", 1L, "Zero"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUANTITY_CHANGE);

        assertThatThrownBy(() -> stockLedgerService.recordImport(100L, -5, "GRN-NEG", 1L, "Negative"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUANTITY_CHANGE);
    }

    @Test
    @DisplayName("recordImport should throw PRODUCT_NOT_FOUND if product does not exist")
    void testRecordImport_ProductNotFound_ThrowsException() {
        when(productRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stockLedgerService.recordImport(999L, 10, "GRN-NONE", 1L, "Not found"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("recordImport with null acting user should record log with null performedBy")
    void testRecordImport_NullUser_RecordsWithNullPerformedBy() {
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));
        when(stockMovementLogRepository.save(any(StockMovementLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockMovementLog result = stockLedgerService.recordImport(100L, 10, "GRN-SYSTEM", null, "Hệ thống tự động nhập");

        assertThat(result.getPerformedBy()).isNull();
        verify(userRepository, never()).findById(any());
    }

    // ==========================================
    // 2. RECORD ORDER DEDUCT (Xuất Kho Bán Hàng)
    // ==========================================

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
    @DisplayName("Boundary Test: recordOrderDeduct exact available stock to 0 should succeed")
    void testRecordOrderDeduct_ExactBalanceToZero_Succeeds() {
        sampleProduct.setStockQuantity(10);
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));
        when(stockMovementLogRepository.save(any(StockMovementLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockMovementLog result = stockLedgerService.recordOrderDeduct(100L, 10, "ORD-EXACT-0", null, "Xuất hết hàng tồn");

        assertThat(result.getCurrentStock()).isEqualTo(0);
        assertThat(sampleProduct.getStockQuantity()).isEqualTo(0);
        verify(productRepository).save(sampleProduct);
    }

    @Test
    @DisplayName("recordOrderDeduct should reject non-positive quantities (0 or negative)")
    void testRecordOrderDeduct_NonPositiveQuantity_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.recordOrderDeduct(100L, 0, "ORD-0", 1L, "Zero"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUANTITY_CHANGE);

        assertThatThrownBy(() -> stockLedgerService.recordOrderDeduct(100L, -10, "ORD-NEG", 1L, "Neg"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUANTITY_CHANGE);
    }

    @Test
    @DisplayName("recordOrderDeduct should throw PRODUCT_NOT_FOUND if product does not exist")
    void testRecordOrderDeduct_ProductNotFound_ThrowsException() {
        when(productRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stockLedgerService.recordOrderDeduct(999L, 5, "ORD-NOT-FOUND", 1L, "None"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    // ==========================================
    // 3. RECORD ORDER CANCELLED RESTOCK (Hoàn Kho)
    // ==========================================

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
    @DisplayName("recordOrderCancelledRestock should reject non-positive quantities (0 or negative)")
    void testRecordOrderCancelledRestock_NonPositiveQuantity_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.recordOrderCancelledRestock(100L, 0, "ORD-CAN-0", 1L, "Zero"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUANTITY_CHANGE);

        assertThatThrownBy(() -> stockLedgerService.recordOrderCancelledRestock(100L, -2, "ORD-CAN-NEG", 1L, "Neg"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUANTITY_CHANGE);
    }

    @Test
    @DisplayName("recordOrderCancelledRestock should reject operations on inactive product")
    void testRecordOrderCancelledRestock_InactiveProduct_ThrowsException() {
        sampleProduct.setIsActive(false);
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));

        assertThatThrownBy(() -> stockLedgerService.recordOrderCancelledRestock(100L, 2, "ORD-CAN-INACT", 1L, "Inactive"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_INACTIVE);
    }

    // ==========================================
    // 4. RECORD MANUAL ADJUSTMENT (Kiểm Kê / Điều Chỉnh)
    // ==========================================

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
    @DisplayName("Boundary Test: recordManualAdjustment exactly reducing stock to 0 should succeed")
    void testRecordManualAdjustment_ExactReductionToZero_Succeeds() {
        sampleProduct.setStockQuantity(8);
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));
        when(stockMovementLogRepository.save(any(StockMovementLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockMovementLog result = stockLedgerService.recordManualAdjustment(100L, -8, "ADJ-ZERO", 1L, "Thanh lý toàn bộ");

        assertThat(result.getCurrentStock()).isEqualTo(0);
        assertThat(sampleProduct.getStockQuantity()).isEqualTo(0);
    }

    @Test
    @DisplayName("recordManualAdjustment should reject zero quantity change")
    void testRecordManualAdjustment_ZeroChange_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.recordManualAdjustment(100L, 0, "ADJ-0", 1L, "Zero change"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_QUANTITY_CHANGE);
    }

    // ==========================================
    // 5. AUDIT TRANSACTIONS HELPER (append)
    // ==========================================

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

        assertThatThrownBy(() -> stockLedgerService.append(
                100L, StockMovementLog.TransactionType.IMPORT, 10, -5, 5, "REF", 1L, "Tồn đầu âm"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NEGATIVE_STOCK_NOT_ALLOWED);
    }

    @Test
    @DisplayName("append should reject null inputs (productId, transactionType, referenceCode)")
    void testAppend_NullInputs_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.append(null, StockMovementLog.TransactionType.IMPORT, 10, 0, 10, "REF", 1L, "Null ID"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);

        assertThatThrownBy(() -> stockLedgerService.append(100L, null, 10, 0, 10, "REF", 1L, "Null Type"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);

        assertThatThrownBy(() -> stockLedgerService.append(100L, StockMovementLog.TransactionType.IMPORT, 10, 0, 10, "   ", 1L, "Blank Ref"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);
    }

    @Test
    @DisplayName("append should succeed and save exact values when valid")
    void testAppend_ValidParameters_Succeeds() {
        when(productRepository.findById(100L)).thenReturn(Optional.of(sampleProduct));
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

        stockLedgerService.append(100L, StockMovementLog.TransactionType.IMPORT, 20, 50, 70, "AUDIT-01", 1L, "Ghi sổ kiểm toán");

        ArgumentCaptor<StockMovementLog> captor = ArgumentCaptor.forClass(StockMovementLog.class);
        verify(stockMovementLogRepository).save(captor.capture());
        StockMovementLog saved = captor.getValue();

        assertThat(saved.getProduct().getId()).isEqualTo(100L);
        assertThat(saved.getTransactionType()).isEqualTo(StockMovementLog.TransactionType.IMPORT);
        assertThat(saved.getQuantityChange()).isEqualTo(20);
        assertThat(saved.getPreviousStock()).isEqualTo(50);
        assertThat(saved.getCurrentStock()).isEqualTo(70);
        assertThat(saved.getReferenceCode()).isEqualTo("AUDIT-01");
        assertThat(saved.getPerformedBy()).isEqualTo(sampleUser);
        assertThat(saved.getNote()).isEqualTo("Ghi sổ kiểm toán");
    }

    @Test
    @DisplayName("append when user is not found in database should save with null performedBy")
    void testAppend_UserNotFound_SavesWithNullUser() {
        when(productRepository.findById(100L)).thenReturn(Optional.of(sampleProduct));
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        stockLedgerService.append(100L, StockMovementLog.TransactionType.IMPORT, 10, 50, 60, "AUDIT-02", 999L, "User missing");

        ArgumentCaptor<StockMovementLog> captor = ArgumentCaptor.forClass(StockMovementLog.class);
        verify(stockMovementLogRepository).save(captor.capture());
        assertThat(captor.getValue().getPerformedBy()).isNull();
    }

    // ==========================================
    // 6. IMMUTABILITY & REPOSITORY VERIFICATION
    // ==========================================

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

    // ==========================================
    // 7. QUERIES & PRESENTATION
    // ==========================================

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

    @Test
    @DisplayName("Query: getLogsByReferenceCode should return matching logs list")
    void testGetLogsByReferenceCode() {
        StockMovementLog log1 = StockMovementLog.builder()
                .id(1L)
                .product(sampleProduct)
                .transactionType(StockMovementLog.TransactionType.IMPORT)
                .quantityChange(20)
                .previousStock(50)
                .currentStock(70)
                .referenceCode("REF-ABC")
                .createdAt(LocalDateTime.now())
                .build();

        when(stockMovementLogRepository.findByReferenceCode("REF-ABC")).thenReturn(List.of(log1));

        List<StockMovementLogResponse> responses = stockLedgerService.getLogsByReferenceCode("REF-ABC");

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getReferenceCode()).isEqualTo("REF-ABC");
        assertThat(responses.get(0).getPerformedByUserName()).isEqualTo("Hệ thống (SYSTEM)");
    }

    @Test
    @DisplayName("Query: getLedgerLogs should return paged logs via findAllWithDetails")
    void testGetLedgerLogs_Paged() {
        StockMovementLog log1 = StockMovementLog.builder()
                .id(1L)
                .product(sampleProduct)
                .transactionType(StockMovementLog.TransactionType.ORDER_DEDUCT)
                .quantityChange(-5)
                .previousStock(50)
                .currentStock(45)
                .referenceCode("ORD-100")
                .createdAt(LocalDateTime.now())
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        when(stockMovementLogRepository.findAllWithDetails(pageable)).thenReturn(new PageImpl<>(List.of(log1)));

        Page<StockMovementLogResponse> page = stockLedgerService.getLedgerLogs(pageable);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getCurrentStock()).isEqualTo(45);
    }

    // ==========================================
    // 8. SANITY CHECKS & OVERFLOW GUARDS
    // ==========================================

    @Test
    @DisplayName("Sanity Check: processMovement should reject null productId")
    void testProcessMovement_NullProductId_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.processMovement(
                null, StockMovementLog.TransactionType.IMPORT, 10, "REF-001", 1L, "Null ID"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);
    }

    @Test
    @DisplayName("Sanity Check: processMovement should reject null transaction type")
    void testProcessMovement_NullType_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.processMovement(
                100L, null, 10, "REF-001", 1L, "Null type"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);
    }

    @Test
    @DisplayName("Sanity Check: processMovement should reject empty referenceCode")
    void testProcessMovement_BlankReferenceCode_ThrowsException() {
        assertThatThrownBy(() -> stockLedgerService.processMovement(
                100L, StockMovementLog.TransactionType.IMPORT, 10, "   ", 1L, "Blank ref"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_DATA);
    }

    @Test
    @DisplayName("Sanity Check: processMovement should reject operations on inactive product")
    void testProcessMovement_InactiveProduct_ThrowsException() {
        sampleProduct.setIsActive(false);
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));

        assertThatThrownBy(() -> stockLedgerService.recordOrderDeduct(100L, 5, "ORD-INACTIVE", 1L, "Sản phẩm ngừng bán"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_INACTIVE);

        verify(productRepository, never()).save(any());
        verify(stockMovementLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Sanity Check: processMovement should reject stock overflow exceeding Integer.MAX_VALUE")
    void testProcessMovement_StockOverflow_ThrowsException() {
        sampleProduct.setStockQuantity(Integer.MAX_VALUE - 5);
        when(productRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sampleProduct));

        assertThatThrownBy(() -> stockLedgerService.processMovement(
                100L, StockMovementLog.TransactionType.IMPORT, 10, "GRN-OVERFLOW", 1L, "Vượt trần tồn kho"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_OVERFLOW);

        verify(productRepository, never()).save(any());
        verify(stockMovementLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Sanity Check: append should reject operations on inactive product")
    void testAppend_InactiveProduct_ThrowsException() {
        sampleProduct.setIsActive(false);
        when(productRepository.findById(100L)).thenReturn(Optional.of(sampleProduct));

        assertThatThrownBy(() -> stockLedgerService.append(
                100L, StockMovementLog.TransactionType.IMPORT, 10, 50, 60, "REF-INACTIVE", 1L, "Inactive"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_INACTIVE);

        verify(stockMovementLogRepository, never()).save(any());
    }
}
