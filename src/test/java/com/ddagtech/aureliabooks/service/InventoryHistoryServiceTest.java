package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.InventoryHistoryFilterRequest;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementResponse;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary.MovementType;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.InventoryMovementRepository;
import com.ddagtech.aureliabooks.dto.response.OrderDetailModalDto;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.OrderItem;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.Optional;

import com.ddagtech.aureliabooks.service.impl.InventoryHistoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryHistoryService Unit Tests (TASK-03)")
class InventoryHistoryServiceTest {

    @Mock
    private InventoryMovementRepository inventoryMovementRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private InventoryHistoryServiceImpl inventoryHistoryService;

    @Nested
    @DisplayName("Legacy history() Tests")
    class LegacyHistoryTests {

        @Test
        @DisplayName("history delegates directly to legacy repository findMovements")
        void history_delegatesQueryToReadOnlyViewRepository() {
            Pageable pageable = PageRequest.of(0, 20);
            LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
            LocalDateTime to = LocalDateTime.of(2026, 10, 18, 0, 0);
            InventoryMovementSummary movement = new InventoryMovementSummary(
                    10L,
                    MovementType.IMPORT,
                    LocalDateTime.of(2026, 10, 15, 9, 30),
                    50,
                    "goods_receipts",
                    1L,
                    100L,
                    "GR-20261015-001",
                    2L
            );
            Page<InventoryMovementSummary> expected = new PageImpl<>(List.of(movement), pageable, 1);

            when(inventoryMovementRepository.findMovements(10L, 2L, from, to, pageable)).thenReturn(expected);

            Page<InventoryMovementSummary> result = inventoryHistoryService.history(10L, 2L, from, to, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().getFirst().referenceCode()).isEqualTo("GR-20261015-001");
            verify(inventoryMovementRepository).findMovements(10L, 2L, from, to, pageable);
        }
    }

    @Nested
    @DisplayName("getInventoryHistory() Tests (Enriched UC25 Query)")
    class GetInventoryHistoryTests {

        @Test
        @DisplayName("Successfully normalizes date boundaries and delegates to searchMovements")
        void getInventoryHistory_withValidDates_normalizesBoundaries() {
            LocalDate startDate = LocalDate.of(2026, 10, 5);
            LocalDate endDate = LocalDate.of(2026, 10, 15);
            Pageable pageable = PageRequest.of(0, 20);

            InventoryHistoryFilterRequest filter = new InventoryHistoryFilterRequest(
                    "book", 100L, MovementType.IMPORT, startDate, endDate, 2L, 0, 20);

            InventoryMovementResponse responseItem = new InventoryMovementResponse(
                    100L, "Sample Book", "893123456", MovementType.IMPORT,
                    LocalDateTime.of(2026, 10, 6, 10, 0), 20,
                    "goods_receipts", 1L, 10L, "GR-001", 2L, "Admin", "admin@store.com", "Note"
            );
            Page<InventoryMovementResponse> pageResult = new PageImpl<>(List.of(responseItem), pageable, 1);

            when(inventoryMovementRepository.searchMovements(eq(filter), any(), any(), eq(pageable)))
                    .thenReturn(pageResult);

            Page<InventoryMovementResponse> result = inventoryHistoryService.getInventoryHistory(filter, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().getFirst().productTitle()).isEqualTo("Sample Book");

            ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

            verify(inventoryMovementRepository).searchMovements(eq(filter), fromCaptor.capture(), toCaptor.capture(), eq(pageable));

            assertThat(fromCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 5, 0, 0, 0));
            assertThat(toCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 16, 0, 0, 0));
        }

        @Test
        @DisplayName("Throws AppException with INVALID_INPUT_DATA when startDate is after endDate (RDS 1.0.E1)")
        void getInventoryHistory_whenStartDateAfterEndDate_throwsAppException() {
            LocalDate startDate = LocalDate.of(2026, 10, 20);
            LocalDate endDate = LocalDate.of(2026, 10, 10);
            Pageable pageable = PageRequest.of(0, 20);

            InventoryHistoryFilterRequest invalidFilter = new InventoryHistoryFilterRequest(
                    null, null, (MovementType) null, startDate, endDate, null, 0, 20);

            assertThatThrownBy(() -> inventoryHistoryService.getInventoryHistory(invalidFilter, pageable))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("Start date must be earlier than or equal to End date.")
                    .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA));

            verifyNoInteractions(inventoryMovementRepository);
        }

        @Test
        @DisplayName("Safely handles null filter and passes null date boundaries")
        void getInventoryHistory_withNullFilter_passesNullBoundariesAndDefaults() {
            Pageable pageable = PageRequest.of(0, 20);
            when(inventoryMovementRepository.searchMovements(any(), isNull(), isNull(), any()))
                    .thenReturn(Page.empty(pageable));

            Page<InventoryMovementResponse> result = inventoryHistoryService.getInventoryHistory(null, null);

            assertThat(result.isEmpty()).isTrue();
            verify(inventoryMovementRepository).searchMovements(any(InventoryHistoryFilterRequest.class), isNull(), isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("Sanitizes invalid pageable sizes to allowable default size 20")
        void getInventoryHistory_sanitizesInvalidPageable() {
            Pageable invalidPageable = mock(Pageable.class);
            when(invalidPageable.getPageNumber()).thenReturn(-1);
            when(invalidPageable.getPageSize()).thenReturn(9999);
            when(inventoryMovementRepository.searchMovements(any(), any(), any(), any()))
                    .thenReturn(Page.empty());

            inventoryHistoryService.getInventoryHistory(new InventoryHistoryFilterRequest(), invalidPageable);

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(inventoryMovementRepository).searchMovements(any(), any(), any(), pageableCaptor.capture());

            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(0);
            assertThat(captured.getPageSize()).isEqualTo(20);
        }

        @Test
        @DisplayName("Preserves valid pageable sizes from allowable whitelist")
        void getInventoryHistory_preservesAllowedPageSize() {
            Pageable validPageable = PageRequest.of(2, 50);
            when(inventoryMovementRepository.searchMovements(any(), any(), any(), any()))
                    .thenReturn(Page.empty());

            inventoryHistoryService.getInventoryHistory(new InventoryHistoryFilterRequest(), validPageable);

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(inventoryMovementRepository).searchMovements(any(), any(), any(), pageableCaptor.capture());

            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(2);
            assertThat(captured.getPageSize()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("getOrderDetail() Tests (L-NEW-02)")
    class GetOrderDetailTests {

        @Test
        @DisplayName("Returns empty Optional when orderId is null")
        void getOrderDetail_whenIdNull_returnsEmpty() {
            Optional<OrderDetailModalDto> result = inventoryHistoryService.getOrderDetail(null);
            assertThat(result).isEmpty();
            verifyNoInteractions(orderRepository);
        }

        @Test
        @DisplayName("Returns empty Optional when order is not found in database")
        void getOrderDetail_whenNotFound_returnsEmpty() {
            when(orderRepository.findByIdWithItemsAndProduct(99L)).thenReturn(Optional.empty());

            Optional<OrderDetailModalDto> result = inventoryHistoryService.getOrderDetail(99L);
            assertThat(result).isEmpty();
            verify(orderRepository).findByIdWithItemsAndProduct(99L);
        }

        @Test
        @DisplayName("Eagerly loads order with items and product to eliminate N+1 queries")
        void getOrderDetail_whenFound_mapsAllFieldsAndItemsProperly() {
            Product p1 = Product.builder().id(101L).barcode("893001").build();
            OrderItem item1 = OrderItem.builder()
                    .id(1L)
                    .product(p1)
                    .productNameSnapshot("Book Title 1")
                    .quantity(2)
                    .unitPriceSnapshot(BigDecimal.valueOf(50000))
                    .build();

            Order order = Order.builder()
                    .id(10L)
                    .orderCode("ORD-10")
                    .orderStatus(Order.OrderStatus.CONFIRMED)
                    .paymentMethod(Order.PaymentMethod.COD)
                    .paymentStatus(Order.PaymentStatus.UNPAID)
                    .shippingRecipientName("John Doe")
                    .shippingPhone("0123456789")
                    .shippingFullAddress("123 Street")
                    .shippingFee(BigDecimal.valueOf(25000))
                    .finalTotalAmount(BigDecimal.valueOf(125000))
                    .stockDeductedAt(LocalDateTime.of(2026, 10, 20, 10, 0))
                    .items(List.of(item1))
                    .build();

            when(orderRepository.findByIdWithItemsAndProduct(10L)).thenReturn(Optional.of(order));

            Optional<OrderDetailModalDto> result = inventoryHistoryService.getOrderDetail(10L);

            assertThat(result).isPresent();
            OrderDetailModalDto dto = result.get();
            assertThat(dto.id()).isEqualTo(10L);
            assertThat(dto.orderCode()).isEqualTo("ORD-10");
            assertThat(dto.orderStatus()).isEqualTo("CONFIRMED");
            assertThat(dto.recipientName()).isEqualTo("John Doe");
            assertThat(dto.items()).hasSize(1);

            OrderDetailModalDto.OrderItemDetailDto itemDto = dto.items().getFirst();
            assertThat(itemDto.productId()).isEqualTo(101L);
            assertThat(itemDto.productTitle()).isEqualTo("Book Title 1");
            assertThat(itemDto.barcode()).isEqualTo("893001");
            assertThat(itemDto.quantity()).isEqualTo(2);
            assertThat(itemDto.unitPrice()).isEqualByComparingTo(BigDecimal.valueOf(50000));
            assertThat(itemDto.subtotal()).isEqualByComparingTo(BigDecimal.valueOf(100000));

            verify(orderRepository).findByIdWithItemsAndProduct(10L);
        }
    }
}