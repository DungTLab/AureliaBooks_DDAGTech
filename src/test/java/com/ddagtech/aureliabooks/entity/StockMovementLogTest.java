package com.ddagtech.aureliabooks.entity;

import com.ddagtech.aureliabooks.dto.response.StockMovementLogResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit Test Suite for {@link StockMovementLog} entity and {@link StockMovementLogResponse} DTO.
 * Verifies DoD 3: Immutable append-only behavior, pre-update/remove lifecycle hooks,
 * and robust mapping to presentation DTO.
 */
class StockMovementLogTest {

    @Test
    @DisplayName("DoD 3: preUpdate must throw UnsupportedOperationException")
    void testPreUpdate_ThrowsException() {
        StockMovementLog log = new StockMovementLog();
        assertThatThrownBy(log::preUpdate)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("strictly immutable and cannot be updated");
    }

    @Test
    @DisplayName("DoD 3: preRemove must throw UnsupportedOperationException")
    void testPreRemove_ThrowsException() {
        StockMovementLog log = new StockMovementLog();
        assertThatThrownBy(log::preRemove)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("strictly immutable and cannot be deleted");
    }

    @Test
    @DisplayName("Entity: Builder, Getters, and Setters should function properly")
    void testEntity_BuilderAndGetters() {
        Product product = Product.builder().id(10L).title("Sách Test").build();
        User user = User.builder().id(5L).fullName("Nguyễn Văn A").build();
        LocalDateTime now = LocalDateTime.now();

        StockMovementLog log = StockMovementLog.builder()
                .id(1L)
                .product(product)
                .transactionType(StockMovementLog.TransactionType.IMPORT)
                .quantityChange(10)
                .previousStock(0)
                .currentStock(10)
                .referenceCode("REF-123")
                .performedBy(user)
                .note("Ghi chú")
                .createdAt(now)
                .build();

        assertThat(log.getId()).isEqualTo(1L);
        assertThat(log.getProduct()).isEqualTo(product);
        assertThat(log.getTransactionType()).isEqualTo(StockMovementLog.TransactionType.IMPORT);
        assertThat(log.getQuantityChange()).isEqualTo(10);
        assertThat(log.getPreviousStock()).isEqualTo(0);
        assertThat(log.getCurrentStock()).isEqualTo(10);
        assertThat(log.getReferenceCode()).isEqualTo("REF-123");
        assertThat(log.getPerformedBy()).isEqualTo(user);
        assertThat(log.getNote()).isEqualTo("Ghi chú");
        assertThat(log.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Enum: TransactionType must contain exactly the 4 required business transaction types")
    void testTransactionType_EnumValues() {
        StockMovementLog.TransactionType[] types = StockMovementLog.TransactionType.values();
        assertThat(types).containsExactlyInAnyOrder(
                StockMovementLog.TransactionType.IMPORT,
                StockMovementLog.TransactionType.ORDER_DEDUCT,
                StockMovementLog.TransactionType.ORDER_CANCELLED_RESTOCK,
                StockMovementLog.TransactionType.MANUAL_ADJUSTMENT
        );
    }

    @Test
    @DisplayName("DTO: fromEntity(null) should return null safely")
    void testResponseDto_FromNullEntity_ReturnsNull() {
        assertThat(StockMovementLogResponse.fromEntity(null)).isNull();
    }

    @Test
    @DisplayName("DTO: fromEntity with full entity details should map all attributes accurately")
    void testResponseDto_FromCompleteEntity() {
        Product product = Product.builder()
                .id(100L)
                .barcode("8930000001")
                .title("Nhà Giả Kim")
                .productType(Product.ProductType.BOOK)
                .build();

        User user = User.builder()
                .id(2L)
                .fullName("Lê Tiến Dũng")
                .build();

        LocalDateTime now = LocalDateTime.now();

        StockMovementLog entity = StockMovementLog.builder()
                .id(99L)
                .product(product)
                .transactionType(StockMovementLog.TransactionType.ORDER_DEDUCT)
                .quantityChange(-2)
                .previousStock(20)
                .currentStock(18)
                .referenceCode("ORD-999")
                .performedBy(user)
                .note("Bán lẻ tại quầy")
                .createdAt(now)
                .build();

        StockMovementLogResponse response = StockMovementLogResponse.fromEntity(entity);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(99L);
        assertThat(response.getProductId()).isEqualTo(100L);
        assertThat(response.getProductBarcode()).isEqualTo("8930000001");
        assertThat(response.getProductTitle()).isEqualTo("Nhà Giả Kim");
        assertThat(response.getProductType()).isEqualTo("BOOK");
        assertThat(response.getTransactionType()).isEqualTo(StockMovementLog.TransactionType.ORDER_DEDUCT);
        assertThat(response.getQuantityChange()).isEqualTo(-2);
        assertThat(response.getPreviousStock()).isEqualTo(20);
        assertThat(response.getCurrentStock()).isEqualTo(18);
        assertThat(response.getReferenceCode()).isEqualTo("ORD-999");
        assertThat(response.getPerformedByUserId()).isEqualTo(2L);
        assertThat(response.getPerformedByUserName()).isEqualTo("Lê Tiến Dũng");
        assertThat(response.getNote()).isEqualTo("Bán lẻ tại quầy");
        assertThat(response.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("DTO: fromEntity with null performedBy should fallback to 'Hệ thống (SYSTEM)'")
    void testResponseDto_NullPerformedBy_FallbacksToSystem() {
        Product product = Product.builder().id(100L).title("Bút Bi").productType(Product.ProductType.STATIONERY).build();

        StockMovementLog entity = StockMovementLog.builder()
                .id(50L)
                .product(product)
                .transactionType(StockMovementLog.TransactionType.ORDER_CANCELLED_RESTOCK)
                .quantityChange(1)
                .previousStock(9)
                .currentStock(10)
                .referenceCode("CANCEL-AUTO")
                .performedBy(null)
                .build();

        StockMovementLogResponse response = StockMovementLogResponse.fromEntity(entity);

        assertThat(response).isNotNull();
        assertThat(response.getPerformedByUserId()).isNull();
        assertThat(response.getPerformedByUserName()).isEqualTo("Hệ thống (SYSTEM)");
        assertThat(response.getProductType()).isEqualTo("STATIONERY");
    }

    @Test
    @DisplayName("DTO: fromEntity with null product should gracefully handle null product fields")
    void testResponseDto_NullProduct_HandlesSafely() {
        StockMovementLog entity = StockMovementLog.builder()
                .id(50L)
                .product(null)
                .transactionType(StockMovementLog.TransactionType.IMPORT)
                .quantityChange(10)
                .previousStock(0)
                .currentStock(10)
                .referenceCode("REF-NOPROD")
                .build();

        StockMovementLogResponse response = StockMovementLogResponse.fromEntity(entity);

        assertThat(response).isNotNull();
        assertThat(response.getProductId()).isNull();
        assertThat(response.getProductBarcode()).isNull();
        assertThat(response.getProductTitle()).isNull();
        assertThat(response.getProductType()).isNull();
    }
}
