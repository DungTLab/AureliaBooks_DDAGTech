package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary.MovementType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class InventoryMovementRepositoryImpl implements InventoryMovementRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String BASE_WHERE = """
            WHERE (:productId IS NULL OR product_id = :productId)
              AND (:actorUserId IS NULL OR actor_user_id = :actorUserId)
              AND (:fromInclusive IS NULL OR occurred_at >= :fromInclusive)
              AND (:toExclusive IS NULL OR occurred_at < :toExclusive)
            """;

    private static final RowMapper<InventoryMovementSummary> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp ts = rs.getTimestamp("occurred_at");
        Long actorId = rs.getObject("actor_user_id") != null ? rs.getLong("actor_user_id") : null;
        return new InventoryMovementSummary(
                rs.getLong("product_id"),
                MovementType.valueOf(rs.getString("movement_type")),
                ts != null ? ts.toLocalDateTime() : null,
                rs.getInt("quantity_change"),
                rs.getString("source_table"),
                rs.getLong("source_id"),
                rs.getLong("source_line_id"),
                rs.getString("reference_code"),
                actorId
        );
    };

    @Override
    public Page<InventoryMovementSummary> findMovements(
            Long productId, Long actorUserId,
            LocalDateTime fromInclusive, LocalDateTime toExclusive, Pageable pageable) {

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("actorUserId", actorUserId)
                .addValue("fromInclusive", fromInclusive)
                .addValue("toExclusive", toExclusive)
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());

        String countSql = "SELECT COUNT(*) FROM inventory_movements " + BASE_WHERE;
        Long totalCount = jdbc.queryForObject(countSql, params, Long.class);
        long total = totalCount != null ? totalCount : 0L;
        if (total == 0) {
            return Page.empty(pageable);
        }

        // Deterministic ordering matching the contract in InventoryMovementRepository.java
        String dataSql = """
                SELECT product_id, movement_type, occurred_at, quantity_change,
                       source_table, source_id, source_line_id, reference_code, actor_user_id
                FROM inventory_movements
                """ + BASE_WHERE + """
                ORDER BY occurred_at DESC, source_table ASC, source_id DESC, source_line_id DESC, movement_type ASC
                LIMIT :limit OFFSET :offset
                """;

        List<InventoryMovementSummary> content = jdbc.query(dataSql, params, ROW_MAPPER);
        return new PageImpl<>(content, pageable, total);
    }
}