package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.request.InventoryHistoryFilterRequest;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementResponse;
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

/**
 * Implementation of {@link InventoryMovementRepository} querying the {@code inventory_movements} view.
 * Ensures strict read-only semantics with parameterized SQL and deterministic ordering.
 */
@Repository
@RequiredArgsConstructor
public class InventoryMovementRepositoryImpl implements InventoryMovementRepository {

    private final NamedParameterJdbcTemplate jdbc;

    // =========================================================================
    // Legacy mapping (Backward compatibility)
    // =========================================================================

    private static final String LEGACY_BASE_WHERE = """
            WHERE (:productId IS NULL OR product_id = :productId)
              AND (:actorUserId IS NULL OR actor_user_id = :actorUserId)
              AND (:fromInclusive IS NULL OR occurred_at >= :fromInclusive)
              AND (:toExclusive IS NULL OR occurred_at < :toExclusive)
            """;

    private static final RowMapper<InventoryMovementSummary> SUMMARY_ROW_MAPPER = (rs, rowNum) -> {
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

        String countSql = "SELECT COUNT(*) FROM inventory_movements " + LEGACY_BASE_WHERE;
        Long totalCount = jdbc.queryForObject(countSql, params, Long.class);
        long total = totalCount != null ? totalCount : 0L;
        if (total == 0) {
            return Page.empty(pageable);
        }

        String dataSql = """
                SELECT product_id, movement_type, occurred_at, quantity_change,
                       source_table, source_id, source_line_id, reference_code, actor_user_id
                FROM inventory_movements
                """ + LEGACY_BASE_WHERE + """
                ORDER BY occurred_at DESC, source_table ASC, source_id DESC, source_line_id DESC, movement_type ASC
                LIMIT :limit OFFSET :offset
                """;

        List<InventoryMovementSummary> content = jdbc.query(dataSql, params, SUMMARY_ROW_MAPPER);
        return new PageImpl<>(content, pageable, total);
    }

    // =========================================================================
    // Enriched Search Query (TASK-02 / UC25)
    // =========================================================================

    private static final String ENRICHED_WHERE = """
            WHERE (:productId IS NULL OR im.product_id = :productId)
              AND (:movementType IS NULL OR im.movement_type = :movementType)
              AND (
                   :actorUserId IS NULL
                   OR (:isSystemActor = true AND im.actor_user_id IS NULL)
                   OR (:isSystemActor = false AND im.actor_user_id = :actorUserId)
              )
              AND (:fromInclusive IS NULL OR im.occurred_at >= :fromInclusive)
              AND (:toExclusive IS NULL OR im.occurred_at < :toExclusive)
              AND (
                   :keywordPattern IS NULL
                   OR LOWER(p.title) LIKE :keywordPattern ESCAPE '\\\\'
                   OR p.barcode LIKE :keywordPattern ESCAPE '\\\\'
                   OR b.isbn LIKE :keywordPattern ESCAPE '\\\\'
                   OR im.reference_code LIKE :keywordPattern ESCAPE '\\\\'
              )
            """;

    private static final RowMapper<InventoryMovementResponse> RESPONSE_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp ts = rs.getTimestamp("occurred_at");
        Long actorId = rs.getObject("actor_user_id") != null ? rs.getLong("actor_user_id") : null;
        String rawType = rs.getString("movement_type");
        MovementType mType = rawType != null ? MovementType.valueOf(rawType) : null;

        return new InventoryMovementResponse(
                rs.getLong("product_id"),
                rs.getString("product_title"),
                rs.getString("product_barcode"),
                mType,
                ts != null ? ts.toLocalDateTime() : null,
                rs.getInt("quantity_change"),
                rs.getString("source_table"),
                rs.getLong("source_id"),
                rs.getLong("source_line_id"),
                rs.getString("reference_code"),
                actorId,
                rs.getString("actor_name"),
                rs.getString("actor_email"),
                rs.getString("note")
        );
    };

    @Override
    public Page<InventoryMovementResponse> searchMovements(
            InventoryHistoryFilterRequest filter,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            Pageable pageable) {

        Long productId = filter != null ? filter.productId() : null;
        String movementType = (filter != null && filter.movementType() != null)
                ? filter.movementType().name()
                : null;
        Long actorId = filter != null ? filter.actorUserId() : null;
        boolean isSystemActor = actorId != null && actorId == -1L;

        String kw = (filter != null && filter.keyword() != null && !filter.keyword().isBlank())
                ? escapeLikePattern(filter.keyword())
                : null;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("movementType", movementType)
                .addValue("actorUserId", actorId)
                .addValue("isSystemActor", isSystemActor)
                .addValue("fromInclusive", fromInclusive)
                .addValue("toExclusive", toExclusive)
                .addValue("keywordPattern", kw)
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());

        String countSql = """
                SELECT COUNT(*)
                FROM inventory_movements im
                JOIN products p ON im.product_id = p.id
                LEFT JOIN books b ON p.id = b.product_id
                """ + ENRICHED_WHERE;

        Long totalCount = jdbc.queryForObject(countSql, params, Long.class);
        long total = totalCount != null ? totalCount : 0L;
        if (total == 0) {
            return Page.empty(pageable);
        }

        String dataSql = """
                SELECT im.product_id,
                       p.title AS product_title,
                       p.barcode AS product_barcode,
                       im.movement_type,
                       im.occurred_at,
                       im.quantity_change,
                       im.source_table,
                       im.source_id,
                       im.source_line_id,
                       im.reference_code,
                       im.actor_user_id,
                       u.full_name AS actor_name,
                       u.email AS actor_email,
                       CASE
                           WHEN im.source_table = 'goods_receipts' THEN gr.note
                           WHEN im.source_table = 'orders' AND im.movement_type = 'ORDER_CANCELLED_RESTOCK' THEN o.cancel_reason
                           ELSE NULL
                       END AS note
                FROM inventory_movements im
                JOIN products p ON im.product_id = p.id
                LEFT JOIN books b ON p.id = b.product_id
                LEFT JOIN users u ON im.actor_user_id = u.id
                LEFT JOIN goods_receipts gr ON im.source_table = 'goods_receipts' AND im.source_id = gr.id
                LEFT JOIN orders o ON im.source_table = 'orders' AND im.source_id = o.id
                """ + ENRICHED_WHERE + """
                ORDER BY im.occurred_at DESC, im.source_table ASC, im.source_id DESC, im.source_line_id DESC, im.movement_type ASC
                LIMIT :limit OFFSET :offset
                """;

        List<InventoryMovementResponse> content = jdbc.query(dataSql, params, RESPONSE_ROW_MAPPER);
        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Escapes SQL LIKE wildcards (%, _) and backslash escape characters to prevent wildcard pollution [L-04].
     *
     * @param raw keyword input
     * @return lowercase escaped LIKE pattern with leading and trailing wildcard
     */
    static String escapeLikePattern(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String sanitized = raw.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + sanitized.toLowerCase() + "%";
    }

}
