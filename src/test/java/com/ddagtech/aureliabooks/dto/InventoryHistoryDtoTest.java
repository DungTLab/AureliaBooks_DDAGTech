package com.ddagtech.aureliabooks.dto;

import com.ddagtech.aureliabooks.dto.request.InventoryHistoryFilterRequest;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementResponse;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary.MovementType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InventoryHistory DTOs Unit Tests (TASK-01)")
class InventoryHistoryDtoTest {

    @Nested
    @DisplayName("InventoryHistoryFilterRequest Tests")
    class FilterRequestTests {

        @Test
        @DisplayName("Default constructor initializes page 0 and default size 20")
        void defaultConstructor_initializesExpectedDefaults() {
            InventoryHistoryFilterRequest request = new InventoryHistoryFilterRequest();

            assertThat(request.keyword()).isNull();
            assertThat(request.productId()).isNull();
            assertThat(request.movementType()).isNull();
            assertThat(request.startDate()).isNull();
            assertThat(request.endDate()).isNull();
            assertThat(request.actorUserId()).isNull();
            assertThat(request.page()).isEqualTo(0);
            assertThat(request.size()).isEqualTo(20);
            assertThat(request.isDateRangeValid()).isTrue();
        }

        @Test
        @DisplayName("Trims keyword whitespace and converts blank to null")
        void keywordSanitization_trimsAndConvertsEmptyToNull() {
            InventoryHistoryFilterRequest withSpaces = new InventoryHistoryFilterRequest(
                    "  Harry Potter  ", null, (MovementType) null, null, null, null, 0, 20);
            assertThat(withSpaces.keyword()).isEqualTo("Harry Potter");

            InventoryHistoryFilterRequest empty = new InventoryHistoryFilterRequest(
                    "   ", null, (MovementType) null, null, null, null, 0, 20);
            assertThat(empty.keyword()).isNull();
        }

        @Test
        @DisplayName("Sanitizes invalid pagination bounds")
        void paginationBounds_clampsToSafeValues() {
            InventoryHistoryFilterRequest invalidBounds = new InventoryHistoryFilterRequest(
                    null, null, (MovementType) null, null, null, null, -5, 9999);

            assertThat(invalidBounds.page()).isEqualTo(0);
            assertThat(invalidBounds.size()).isEqualTo(20);

            InventoryHistoryFilterRequest validFifty = new InventoryHistoryFilterRequest(
                    null, null, (MovementType) null, null, null, null, 2, 50);
            assertThat(validFifty.page()).isEqualTo(2);
            assertThat(validFifty.size()).isEqualTo(50);
        }

        @Test
        @DisplayName("Validates chronological start and end date logic (BR / Exception 1.0.E1)")
        void dateRangeValidation_enforcesChronology() {
            LocalDate start = LocalDate.of(2026, 10, 1);
            LocalDate end = LocalDate.of(2026, 10, 15);

            InventoryHistoryFilterRequest validRange = new InventoryHistoryFilterRequest(
                    null, null, (MovementType) null, start, end, null, 0, 20);
            assertThat(validRange.isDateRangeValid()).isTrue();

            InventoryHistoryFilterRequest sameDay = new InventoryHistoryFilterRequest(
                    null, null, (MovementType) null, start, start, null, 0, 20);
            assertThat(sameDay.isDateRangeValid()).isTrue();

            InventoryHistoryFilterRequest invalidRange = new InventoryHistoryFilterRequest(
                    null, null, (MovementType) null, end, start, null, 0, 20);
            assertThat(invalidRange.isDateRangeValid()).isFalse();

            InventoryHistoryFilterRequest oneDateNull = new InventoryHistoryFilterRequest(
                    null, null, (MovementType) null, start, null, null, 0, 20);
            assertThat(oneDateNull.isDateRangeValid()).isTrue();
        }

        @Test
        @DisplayName("Validates that dates cannot be set in the future")
        void futureDateValidation_detectsFutureDates() {
            LocalDate today = LocalDate.now();
            LocalDate tomorrow = today.plusDays(1);
            LocalDate yesterday = today.minusDays(1);

            InventoryHistoryFilterRequest pastRequest = new InventoryHistoryFilterRequest(
                    null, null, (MovementType) null, yesterday, today, null, 0, 20);
            assertThat(pastRequest.isNotFutureDate()).isTrue();

            InventoryHistoryFilterRequest futureRequest = new InventoryHistoryFilterRequest(
                    null, null, (MovementType) null, today, tomorrow, null, 0, 20);
            assertThat(futureRequest.isNotFutureDate()).isFalse();
        }

        @Test
        @DisplayName("Parses string movement classification into Enum or null")
        void parseMovementType_handlesVariousInputs() {
            assertThat(InventoryHistoryFilterRequest.parseMovementType("IMPORT")).isEqualTo(MovementType.IMPORT);
            assertThat(InventoryHistoryFilterRequest.parseMovementType("order_deduct")).isEqualTo(MovementType.ORDER_DEDUCT);
            assertThat(InventoryHistoryFilterRequest.parseMovementType("ORDER_CANCELLED_RESTOCK")).isEqualTo(MovementType.ORDER_CANCELLED_RESTOCK);
            assertThat(InventoryHistoryFilterRequest.parseMovementType("ORDER_RETURNED_RESTOCK")).isEqualTo(MovementType.ORDER_RETURNED_RESTOCK);

            assertThat(InventoryHistoryFilterRequest.parseMovementType("ALL")).isNull();
            assertThat(InventoryHistoryFilterRequest.parseMovementType("all")).isNull();
            assertThat(InventoryHistoryFilterRequest.parseMovementType("")).isNull();
            assertThat(InventoryHistoryFilterRequest.parseMovementType("   ")).isNull();
            assertThat(InventoryHistoryFilterRequest.parseMovementType(null)).isNull();
            assertThat(InventoryHistoryFilterRequest.parseMovementType("NON_EXISTING")).isNull();
        }

        @Test
        @DisplayName("Overloaded string constructor correctly delegates parsing")
        void stringOverloadedConstructor_worksCorrectly() {
            InventoryHistoryFilterRequest request = new InventoryHistoryFilterRequest(
                    "book", 10L, "IMPORT", null, null, 2L, 1, 50);

            assertThat(request.movementType()).isEqualTo(MovementType.IMPORT);
            assertThat(request.productId()).isEqualTo(10L);
            assertThat(request.actorUserId()).isEqualTo(2L);
            assertThat(request.page()).isEqualTo(1);
            assertThat(request.size()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("InventoryMovementResponse Tests")
    class MovementResponseTests {

        @Test
        @DisplayName("Formats positive and negative quantity deltas with proper signs")
        void formattedQuantityChange_addsPlusForPositiveAndPreservesMinus() {
            InventoryMovementResponse inflow = new InventoryMovementResponse(
                    1L, "Book A", "893123456", MovementType.IMPORT,
                    LocalDateTime.now(), 25, "goods_receipts", 1L, 10L, "GR-001",
                    2L, "Admin User", "admin@aureliabooks.com", "Nhập hàng"
            );
            assertThat(inflow.formattedQuantityChange()).isEqualTo("+25");
            assertThat(inflow.isInflow()).isTrue();
            assertThat(inflow.isOutflow()).isFalse();

            InventoryMovementResponse outflow = new InventoryMovementResponse(
                    1L, "Book A", "893123456", MovementType.ORDER_DEDUCT,
                    LocalDateTime.now(), -3, "orders", 5L, 50L, "ORD-001",
                    10L, "Customer", "cust@example.com", null
            );
            assertThat(outflow.formattedQuantityChange()).isEqualTo("-3");
            assertThat(outflow.isInflow()).isFalse();
            assertThat(outflow.isOutflow()).isTrue();

            InventoryMovementResponse zero = new InventoryMovementResponse(
                    1L, "Book A", "893123456", MovementType.IMPORT,
                    LocalDateTime.now(), 0, "goods_receipts", 1L, 10L, "GR-001",
                    null, null, null, null
            );
            assertThat(zero.formattedQuantityChange()).isEqualTo("0");
            assertThat(zero.isInflow()).isFalse();
            assertThat(zero.isOutflow()).isFalse();
        }

        @Test
        @DisplayName("Resolves display actor with name, email, or fallback to 'Hệ thống'")
        void displayActor_resolvesFallbackChain() {
            InventoryMovementResponse withName = new InventoryMovementResponse(
                    1L, "A", "B", MovementType.IMPORT, LocalDateTime.now(), 10,
                    "goods_receipts", 1L, 1L, "GR-1", 2L, "Nguyễn Văn A", "a@test.com", null
            );
            assertThat(withName.displayActor()).isEqualTo("Nguyễn Văn A");

            InventoryMovementResponse withOnlyEmail = new InventoryMovementResponse(
                    1L, "A", "B", MovementType.IMPORT, LocalDateTime.now(), 10,
                    "goods_receipts", 1L, 1L, "GR-1", 2L, null, "a@test.com", null
            );
            assertThat(withOnlyEmail.displayActor()).isEqualTo("a@test.com");

            InventoryMovementResponse systemEvent = new InventoryMovementResponse(
                    1L, "A", "B", MovementType.ORDER_CANCELLED_RESTOCK, LocalDateTime.now(), 5,
                    "orders", 1L, 1L, "ORD-1", null, null, null, "Timeout daemon"
            );
            assertThat(systemEvent.displayActor()).isEqualTo("Hệ thống");
        }

        @Test
        @DisplayName("Factory method fromSummary copies summary attributes accurately")
        void fromSummary_createsEnrichedResponse() {
            LocalDateTime now = LocalDateTime.of(2026, 10, 15, 10, 30);
            InventoryMovementSummary summary = new InventoryMovementSummary(
                    101L, MovementType.ORDER_RETURNED_RESTOCK, now, 2,
                    "orders", 99L, 199L, "ORD-20261015-099", 5L
            );

            InventoryMovementResponse response = InventoryMovementResponse.fromSummary(
                    summary, "Bộ SGK Lớp 10", "9786040001", "Trần Văn Staff", "staff@store.com", "Hàng nguyên vẹn"
            );

            assertThat(response).isNotNull();
            assertThat(response.productId()).isEqualTo(101L);
            assertThat(response.productTitle()).isEqualTo("Bộ SGK Lớp 10");
            assertThat(response.productBarcode()).isEqualTo("9786040001");
            assertThat(response.movementType()).isEqualTo(MovementType.ORDER_RETURNED_RESTOCK);
            assertThat(response.occurredAt()).isEqualTo(now);
            assertThat(response.quantityChange()).isEqualTo(2);
            assertThat(response.sourceTable()).isEqualTo("orders");
            assertThat(response.sourceId()).isEqualTo(99L);
            assertThat(response.sourceLineId()).isEqualTo(199L);
            assertThat(response.referenceCode()).isEqualTo("ORD-20261015-099");
            assertThat(response.actorUserId()).isEqualTo(5L);
            assertThat(response.actorName()).isEqualTo("Trần Văn Staff");
            assertThat(response.actorEmail()).isEqualTo("staff@store.com");
            assertThat(response.note()).isEqualTo("Hàng nguyên vẹn");
        }
    }
}
