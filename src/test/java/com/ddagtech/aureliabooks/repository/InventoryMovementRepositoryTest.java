package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.request.InventoryHistoryFilterRequest;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementResponse;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary.MovementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryMovementRepository Unit Tests (TASK-02)")
class InventoryMovementRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private InventoryMovementRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new InventoryMovementRepositoryImpl(jdbc);
    }

    @Nested
    @DisplayName("searchMovements Tests (Enriched View Query)")
    class SearchMovementsTests {

        @Test
        @DisplayName("Returns empty page immediately when count is zero without running data query")
        void searchMovements_whenCountIsZero_returnsEmptyPage() {
            Pageable pageable = PageRequest.of(0, 20);
            InventoryHistoryFilterRequest filter = new InventoryHistoryFilterRequest();

            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(0L);

            Page<InventoryMovementResponse> result = repository.searchMovements(filter, null, null, pageable);

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.getTotalElements()).isZero();
            verify(jdbc, times(1)).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
            verify(jdbc, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
        }

        @Test
        @DisplayName("Executes data query with full filter parameters and maps results properly")
        void searchMovements_withFullFilters_bindsAllParameters() {
            Pageable pageable = PageRequest.of(1, 10);
            LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
            LocalDateTime to = LocalDateTime.of(2026, 10, 20, 0, 0);

            InventoryHistoryFilterRequest filter = new InventoryHistoryFilterRequest(
                    "doraemon",
                    100L,
                    MovementType.IMPORT,
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 19),
                    2L,
                    1,
                    10
            );

            InventoryMovementResponse movement = new InventoryMovementResponse(
                    100L, "Doraemon Vol 1", "8935244812345", MovementType.IMPORT,
                    LocalDateTime.of(2026, 10, 10, 14, 30), 50,
                    "goods_receipts", 1L, 10L, "GR-20261010-001",
                    2L, "Nguyen Manager", "manager@aureliabooks.com", "Nhap sach kim dong"
            );

            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(15L);

            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                    .thenReturn(java.util.Collections.nCopies(5, movement));

            Page<InventoryMovementResponse> result = repository.searchMovements(filter, from, to, pageable);

            assertThat(result.getTotalElements()).isEqualTo(15L);
            assertThat(result.getTotalPages()).isEqualTo(2);
            assertThat(result.getContent()).hasSize(5);

            InventoryMovementResponse item = result.getContent().getFirst();
            assertThat(item.productId()).isEqualTo(100L);
            assertThat(item.productTitle()).isEqualTo("Doraemon Vol 1");
            assertThat(item.productBarcode()).isEqualTo("8935244812345");
            assertThat(item.movementType()).isEqualTo(MovementType.IMPORT);
            assertThat(item.quantityChange()).isEqualTo(50);
            assertThat(item.referenceCode()).isEqualTo("GR-20261010-001");
            assertThat(item.actorName()).isEqualTo("Nguyen Manager");
            assertThat(item.note()).isEqualTo("Nhap sach kim dong");

            ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbc).query(sqlCaptor.capture(), captor.capture(), any(RowMapper.class));

            String executedSql = sqlCaptor.getValue();
            // L-01: Ensure cancel_reason is only mapped for ORDER_CANCELLED_RESTOCK, not for normal sales ORDER_DEDUCT
            assertThat(executedSql).contains("WHEN im.source_table = 'orders' AND im.movement_type = 'ORDER_CANCELLED_RESTOCK' THEN o.cancel_reason");

            // L-03: Ensure B-Tree indexes are not wiped out by LOWER() function calls
            assertThat(executedSql).contains("p.barcode LIKE :keywordPattern ESCAPE '\\\\'");
            assertThat(executedSql).contains("b.isbn LIKE :keywordPattern ESCAPE '\\\\'");
            assertThat(executedSql).doesNotContain("LOWER(p.barcode)");
            assertThat(executedSql).doesNotContain("LOWER(b.isbn)");

            MapSqlParameterSource bound = captor.getValue();
            assertThat(bound.getValue("productId")).isEqualTo(100L);
            assertThat(bound.getValue("movementType")).isEqualTo("IMPORT");
            assertThat(bound.getValue("actorUserId")).isEqualTo(2L);
            assertThat(bound.getValue("isSystemActor")).isEqualTo(false);
            assertThat(bound.getValue("fromInclusive")).isEqualTo(from);
            assertThat(bound.getValue("toExclusive")).isEqualTo(to);
            assertThat(bound.getValue("keywordPattern")).isEqualTo("%doraemon%");
            assertThat(bound.getValue("limit")).isEqualTo(10);
            assertThat(bound.getValue("offset")).isEqualTo(10L);
        }

        @Test
        @DisplayName("Escapes special SQL wildcard characters (%, _, \\) properly (L-04)")
        void escapeLikePattern_escapesWildcards() {
            String input = "100%_Special\\Book";
            String escaped = InventoryMovementRepositoryImpl.escapeLikePattern(input);
            assertThat(escaped).isEqualTo("%100\\%\\_special\\\\book%");
        }

        @Test
        @DisplayName("Handles system actor (-1) by setting isSystemActor flag to true")
        void searchMovements_withSystemActor_setsSystemFlag() {
            Pageable pageable = PageRequest.of(0, 20);
            InventoryHistoryFilterRequest filter = new InventoryHistoryFilterRequest(
                    null, null, (MovementType) null, null, null, -1L, 0, 20);

            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(1L);
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                    .thenReturn(List.of());

            repository.searchMovements(filter, null, null, pageable);

            ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            verify(jdbc).query(anyString(), captor.capture(), any(RowMapper.class));

            MapSqlParameterSource bound = captor.getValue();
            assertThat(bound.getValue("actorUserId")).isEqualTo(-1L);
            assertThat(bound.getValue("isSystemActor")).isEqualTo(true);
        }

        @Test
        @DisplayName("Handles completely null filter safely with default pagination parameters")
        void searchMovements_withNullFilter_runsSafely() {
            Pageable pageable = PageRequest.of(0, 20);

            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(1L);
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                    .thenReturn(List.of());

            repository.searchMovements(null, null, null, pageable);

            ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            verify(jdbc).query(anyString(), captor.capture(), any(RowMapper.class));

            MapSqlParameterSource bound = captor.getValue();
            assertThat(bound.getValue("productId")).isNull();
            assertThat(bound.getValue("movementType")).isNull();
            assertThat(bound.getValue("actorUserId")).isNull();
            assertThat(bound.getValue("isSystemActor")).isEqualTo(false);
            assertThat(bound.getValue("keywordPattern")).isNull();
        }
    }

    @Nested
    @DisplayName("Legacy findMovements Tests")
    class LegacyFindMovementsTests {

        @Test
        @DisplayName("findMovements queries inventory_movements summary rows")
        void findMovements_delegatesToJdbcTemplate() {
            Pageable pageable = PageRequest.of(0, 20);
            InventoryMovementSummary summary = new InventoryMovementSummary(
                    1L, MovementType.ORDER_DEDUCT, LocalDateTime.now(), -2,
                    "orders", 10L, 20L, "ORD-001", 5L
            );

            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(1L);
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                    .thenReturn(List.of(summary));

            Page<InventoryMovementSummary> result = repository.findMovements(1L, 5L, null, null, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1L);
            assertThat(result.getContent().getFirst().referenceCode()).isEqualTo("ORD-001");
        }
    }
}
