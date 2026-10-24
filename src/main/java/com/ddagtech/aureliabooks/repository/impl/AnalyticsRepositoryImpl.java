package com.ddagtech.aureliabooks.repository.impl;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.AnalyticsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class AnalyticsRepositoryImpl implements AnalyticsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public RevenueSummaryKpiDto queryRevenueSummaryKpis(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime) {
        String sql = """
                SELECT 
                    COUNT(o.id) AS total_orders,
                    COALESCE(SUM(o.final_total_amount), 0.00) AS gross_revenue,
                    COALESCE(SUM(o.shipping_fee), 0.00) AS total_shipping_fee,
                    COALESCE(AVG(o.final_total_amount), 0.00) AS average_order_value
                FROM orders o
                WHERE o.order_status = 'DELIVERED'
                  AND (:startDateTime IS NULL OR o.created_at >= :startDateTime)
                  AND (:endExclusiveDateTime IS NULL OR o.created_at < :endExclusiveDateTime)
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDateTime", startDateTime)
                .addValue("endExclusiveDateTime", endExclusiveDateTime);

        return jdbc.queryForObject(sql, params, (rs, rowNum) -> {
            long total = rs.getLong("total_orders");
            BigDecimal gross = rs.getBigDecimal("gross_revenue");
            BigDecimal shipping = rs.getBigDecimal("total_shipping_fee");
            BigDecimal aov = rs.getBigDecimal("average_order_value");

            return RevenueSummaryKpiDto.builder()
                    .totalOrders(total)
                    .grossRevenue(gross != null ? gross : BigDecimal.ZERO)
                    .totalShippingFee(shipping != null ? shipping : BigDecimal.ZERO)
                    .averageOrderValue(aov != null ? aov : BigDecimal.ZERO)
                    .build();
        });
    }

    @Override
    public List<DailyRevenueDto> queryDailyRevenueTrends(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime) {
        String sql = """
                SELECT 
                    CAST(o.created_at AS DATE) AS order_date,
                    COUNT(o.id) AS daily_order_count,
                    COALESCE(SUM(o.final_total_amount), 0.00) AS daily_revenue
                FROM orders o
                WHERE o.order_status = 'DELIVERED'
                  AND (:startDateTime IS NULL OR o.created_at >= :startDateTime)
                  AND (:endExclusiveDateTime IS NULL OR o.created_at < :endExclusiveDateTime)
                GROUP BY CAST(o.created_at AS DATE)
                ORDER BY order_date ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDateTime", startDateTime)
                .addValue("endExclusiveDateTime", endExclusiveDateTime);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            Date sqlDate = rs.getDate("order_date");
            LocalDate date = sqlDate != null ? sqlDate.toLocalDate() : null;
            long count = rs.getLong("daily_order_count");
            BigDecimal rev = rs.getBigDecimal("daily_revenue");

            return DailyRevenueDto.builder()
                    .date(date)
                    .orderCount(count)
                    .revenue(rev != null ? rev : BigDecimal.ZERO)
                    .build();
        });
    }

    @Override
    public List<DailyRevenueDto> queryMonthlyRevenueTrends(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime) {
        String sql = """
                SELECT 
                    DATE_FORMAT(o.created_at, '%Y-%m') AS order_month,
                    COUNT(o.id) AS monthly_order_count,
                    COALESCE(SUM(o.final_total_amount), 0.00) AS monthly_revenue
                FROM orders o
                WHERE o.order_status = 'DELIVERED'
                  AND (:startDateTime IS NULL OR o.created_at >= :startDateTime)
                  AND (:endExclusiveDateTime IS NULL OR o.created_at < :endExclusiveDateTime)
                GROUP BY DATE_FORMAT(o.created_at, '%Y-%m')
                ORDER BY order_month ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDateTime", startDateTime)
                .addValue("endExclusiveDateTime", endExclusiveDateTime);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            String monthStr = rs.getString("order_month"); // "YYYY-MM"
            LocalDate date = (monthStr != null && monthStr.length() == 7)
                    ? LocalDate.parse(monthStr + "-01")
                    : null;
            String periodLabel = (monthStr != null && monthStr.length() == 7)
                    ? (monthStr.substring(5, 7) + "/" + monthStr.substring(0, 4))
                    : "";
            long count = rs.getLong("monthly_order_count");
            BigDecimal rev = rs.getBigDecimal("monthly_revenue");

            return DailyRevenueDto.builder()
                    .date(date)
                    .periodLabel(periodLabel)
                    .orderCount(count)
                    .revenue(rev != null ? rev : BigDecimal.ZERO)
                    .build();
        });
    }

    @Override
    public PaymentBreakdownDto queryPaymentMethodBreakdown(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime) {
        String sql = """
                SELECT 
                    o.payment_method,
                    COUNT(o.id) AS transaction_count,
                    COALESCE(SUM(o.final_total_amount), 0.00) AS channel_revenue
                FROM orders o
                WHERE o.order_status = 'DELIVERED'
                  AND (:startDateTime IS NULL OR o.created_at >= :startDateTime)
                  AND (:endExclusiveDateTime IS NULL OR o.created_at < :endExclusiveDateTime)
                GROUP BY o.payment_method
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDateTime", startDateTime)
                .addValue("endExclusiveDateTime", endExclusiveDateTime);

        PaymentBreakdownDto result = new PaymentBreakdownDto();

        jdbc.query(sql, params, rs -> {
            String methodStr = rs.getString("payment_method");
            long count = rs.getLong("transaction_count");
            BigDecimal revenue = rs.getBigDecimal("channel_revenue");
            if (revenue == null) {
                revenue = BigDecimal.ZERO;
            }

            if ("COD".equalsIgnoreCase(methodStr)) {
                result.setCodOrdersCount(count);
                result.setCodRevenue(revenue);
            } else if ("VNPAY".equalsIgnoreCase(methodStr)) {
                result.setVnpayOrdersCount(count);
                result.setVnpayRevenue(revenue);
            }
        });

        BigDecimal total = result.getCodRevenue().add(result.getVnpayRevenue());
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            double percent = result.getVnpayRevenue()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(total, 2, RoundingMode.HALF_UP)
                    .doubleValue();
            result.setElectronicSharePercent(percent);
        } else {
            result.setElectronicSharePercent(0.0);
        }

        return result;
    }

    @Override
    public List<TopProductSaleDto> queryTopSellingProducts(Product.ProductType productType, int limit,
                                                           LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime) {
        String sql = """
                SELECT 
                    p.id AS product_id,
                    p.barcode AS barcode,
                    p.title AS title,
                    p.product_type AS product_type,
                    p.main_image_url AS main_image_url,
                    COALESCE(SUM(oi.quantity), 0) AS total_quantity_sold,
                    COALESCE(SUM(oi.quantity * oi.unit_price_snapshot), 0.00) AS total_revenue
                FROM order_items oi
                JOIN orders o ON oi.order_id = o.id
                JOIN products p ON oi.product_id = p.id
                WHERE o.order_status = 'DELIVERED'
                  AND p.product_type = :productType
                  AND (:startDateTime IS NULL OR o.created_at >= :startDateTime)
                  AND (:endExclusiveDateTime IS NULL OR o.created_at < :endExclusiveDateTime)
                GROUP BY p.id, p.barcode, p.title, p.product_type, p.main_image_url
                ORDER BY total_quantity_sold DESC, total_revenue DESC
                LIMIT :limit
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("productType", productType.name())
                .addValue("limit", limit > 0 ? limit : 5)
                .addValue("startDateTime", startDateTime)
                .addValue("endExclusiveDateTime", endExclusiveDateTime);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            String typeStr = rs.getString("product_type");
            Product.ProductType type = typeStr != null ? Product.ProductType.valueOf(typeStr) : productType;

            return TopProductSaleDto.builder()
                    .productId(rs.getLong("product_id"))
                    .barcode(rs.getString("barcode"))
                    .title(rs.getString("title"))
                    .productType(type)
                    .mainImageUrl(rs.getString("main_image_url"))
                    .totalQuantitySold(rs.getLong("total_quantity_sold"))
                    .totalRevenue(rs.getBigDecimal("total_revenue"))
                    .build();
        });
    }

    @Override
    public List<RevenueOrderSummaryDto> queryDeliveredOrders(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime, int limit) {
        String sql = """
                SELECT 
                    o.order_code,
                    o.shipping_recipient_name,
                    o.shipping_phone,
                    o.payment_method,
                    o.created_at,
                    o.delivered_at,
                    o.shipping_fee,
                    o.final_total_amount
                FROM orders o
                WHERE o.order_status = 'DELIVERED'
                  AND (:startDateTime IS NULL OR o.created_at >= :startDateTime)
                  AND (:endExclusiveDateTime IS NULL OR o.created_at < :endExclusiveDateTime)
                ORDER BY o.created_at DESC
                LIMIT :limit
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDateTime", startDateTime)
                .addValue("endExclusiveDateTime", endExclusiveDateTime)
                .addValue("limit", limit > 0 ? limit : 500);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            Timestamp createdTs = rs.getTimestamp("created_at");
            Timestamp deliveredTs = rs.getTimestamp("delivered_at");
            String methodStr = rs.getString("payment_method");
            Order.PaymentMethod method = methodStr != null ? Order.PaymentMethod.valueOf(methodStr) : null;

            return RevenueOrderSummaryDto.builder()
                    .orderCode(rs.getString("order_code"))
                    .recipientName(rs.getString("shipping_recipient_name"))
                    .phone(rs.getString("shipping_phone"))
                    .paymentMethod(method)
                    .createdAt(createdTs != null ? createdTs.toLocalDateTime() : null)
                    .deliveredAt(deliveredTs != null ? deliveredTs.toLocalDateTime() : null)
                    .shippingFee(rs.getBigDecimal("shipping_fee"))
                    .finalTotalAmount(rs.getBigDecimal("final_total_amount"))
                    .build();
        });
    }

    @Override
    public List<RevenueOrderSummaryDto> queryAllDeliveredOrders(LocalDateTime startDateTime, LocalDateTime endExclusiveDateTime) {
        String sql = """
                SELECT 
                    o.order_code,
                    o.shipping_recipient_name,
                    o.shipping_phone,
                    o.payment_method,
                    o.created_at,
                    o.delivered_at,
                    o.shipping_fee,
                    o.final_total_amount
                FROM orders o
                WHERE o.order_status = 'DELIVERED'
                  AND (:startDateTime IS NULL OR o.created_at >= :startDateTime)
                  AND (:endExclusiveDateTime IS NULL OR o.created_at < :endExclusiveDateTime)
                ORDER BY o.created_at DESC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDateTime", startDateTime)
                .addValue("endExclusiveDateTime", endExclusiveDateTime);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            Timestamp createdTs = rs.getTimestamp("created_at");
            Timestamp deliveredTs = rs.getTimestamp("delivered_at");
            String methodStr = rs.getString("payment_method");
            Order.PaymentMethod method = methodStr != null ? Order.PaymentMethod.valueOf(methodStr) : null;

            return RevenueOrderSummaryDto.builder()
                    .orderCode(rs.getString("order_code"))
                    .recipientName(rs.getString("shipping_recipient_name"))
                    .phone(rs.getString("shipping_phone"))
                    .paymentMethod(method)
                    .createdAt(createdTs != null ? createdTs.toLocalDateTime() : null)
                    .deliveredAt(deliveredTs != null ? deliveredTs.toLocalDateTime() : null)
                    .shippingFee(rs.getBigDecimal("shipping_fee"))
                    .finalTotalAmount(rs.getBigDecimal("final_total_amount"))
                    .build();
        });
    }

    @Override
    public List<StockValuationItemDto> queryStockValuationReport() {
        String sql = """
                SELECT 
                    p.id AS product_id,
                    p.barcode,
                    p.title,
                    c.name AS category_name,
                    p.product_type,
                    p.is_active,
                    p.stock_quantity,
                    p.original_cost,
                    p.price,
                    (p.stock_quantity * p.original_cost) AS total_cost_value,
                    (p.stock_quantity * p.price) AS total_retail_value,
                    p.created_at
                FROM products p
                LEFT JOIN categories c ON p.category_id = c.id
                WHERE (p.is_active = TRUE OR p.stock_quantity > 0)
                ORDER BY total_cost_value DESC
                """;

        LocalDate today = LocalDate.now();

        return jdbc.query(sql, (rs, rowNum) -> {
            int stock = rs.getInt("stock_quantity");
            BigDecimal cost = rs.getBigDecimal("original_cost");
            BigDecimal price = rs.getBigDecimal("price");
            BigDecimal totalCost = rs.getBigDecimal("total_cost_value");
            BigDecimal totalRetail = rs.getBigDecimal("total_retail_value");
            boolean isActive = rs.getBoolean("is_active");

            String stockStatus;
            if (stock <= 0) {
                stockStatus = "HẾT HÀNG";
            } else if (stock <= 10) {
                stockStatus = "CẢNH BÁO TỒN THẤP";
            } else {
                stockStatus = "ĐỦ HÀNG";
            }

            Timestamp createdTs = rs.getTimestamp("created_at");
            String agingTier = "HEALTHY";
            if (createdTs != null) {
                long days = ChronoUnit.DAYS.between(createdTs.toLocalDateTime().toLocalDate(), today);
                if (days > 180) {
                    agingTier = "DEAD_STOCK";
                } else if (days > 60) {
                    agingTier = "SLOW_MOVING";
                }
            }

            String typeStr = rs.getString("product_type");
            Product.ProductType productType = typeStr != null ? Product.ProductType.valueOf(typeStr) : null;

            return StockValuationItemDto.builder()
                    .productId(rs.getLong("product_id"))
                    .barcode(rs.getString("barcode"))
                    .title(rs.getString("title"))
                    .categoryName(rs.getString("category_name"))
                    .productType(productType)
                    .isActive(isActive)
                    .stockQuantity(stock)
                    .originalCost(cost != null ? cost : BigDecimal.ZERO)
                    .price(price != null ? price : BigDecimal.ZERO)
                    .totalCostValue(totalCost != null ? totalCost : BigDecimal.ZERO)
                    .totalRetailValue(totalRetail != null ? totalRetail : BigDecimal.ZERO)
                    .agingTier(agingTier)
                    .stockStatus(stockStatus)
                    .build();
        });
    }

    @Override
    public List<StockMovementReportItemDto> queryPeriodStockMovementReport(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        String sql = """
                SELECT 
                    p.id AS product_id,
                    p.barcode,
                    p.title,
                    c.name AS category_name,
                    p.product_type,
                    p.is_active,
                    p.original_cost,
                    p.price,
                    COALESCE(m.opening_stock, 0) AS opening_stock,
                    COALESCE(m.import_qty, 0) AS import_qty,
                    COALESCE(m.order_deduct_qty, 0) AS order_deduct_qty,
                    COALESCE(m.cancel_restore_qty, 0) AS cancel_restore_qty,
                    COALESCE(m.return_restore_qty, 0) AS return_restore_qty,
                    (COALESCE(m.opening_stock, 0) + COALESCE(m.import_qty, 0) + COALESCE(m.cancel_restore_qty, 0) + COALESCE(m.return_restore_qty, 0) - COALESCE(m.order_deduct_qty, 0)) AS closing_stock
                FROM products p
                LEFT JOIN categories c ON p.category_id = c.id
                LEFT JOIN (
                    SELECT 
                        product_id,
                        SUM(CASE WHEN (:fromInclusive IS NOT NULL AND occurred_at < :fromInclusive) THEN quantity_change ELSE 0 END) AS opening_stock,
                        SUM(CASE WHEN (:fromInclusive IS NULL OR occurred_at >= :fromInclusive) AND (:toExclusive IS NULL OR occurred_at < :toExclusive) AND movement_type = 'IMPORT' THEN quantity_change ELSE 0 END) AS import_qty,
                        SUM(CASE WHEN (:fromInclusive IS NULL OR occurred_at >= :fromInclusive) AND (:toExclusive IS NULL OR occurred_at < :toExclusive) AND movement_type = 'ORDER_DEDUCT' THEN -quantity_change ELSE 0 END) AS order_deduct_qty,
                        SUM(CASE WHEN (:fromInclusive IS NULL OR occurred_at >= :fromInclusive) AND (:toExclusive IS NULL OR occurred_at < :toExclusive) AND movement_type = 'ORDER_CANCELLED_RESTOCK' THEN quantity_change ELSE 0 END) AS cancel_restore_qty,
                        SUM(CASE WHEN (:fromInclusive IS NULL OR occurred_at >= :fromInclusive) AND (:toExclusive IS NULL OR occurred_at < :toExclusive) AND movement_type = 'ORDER_RETURNED_RESTOCK' THEN quantity_change ELSE 0 END) AS return_restore_qty
                    FROM inventory_movements
                    WHERE (:toExclusive IS NULL OR occurred_at < :toExclusive)
                    GROUP BY product_id
                ) m ON p.id = m.product_id
                ORDER BY p.id ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fromInclusive", fromInclusive)
                .addValue("toExclusive", toExclusive);

        return jdbc.query(sql, params, (rs, rowNum) -> {
            long productId = rs.getLong("product_id");
            String barcode = rs.getString("barcode");
            String title = rs.getString("title");
            String catName = rs.getString("category_name");
            String typeStr = rs.getString("product_type");
            Product.ProductType productType = typeStr != null ? Product.ProductType.valueOf(typeStr) : null;
            boolean isActive = rs.getBoolean("is_active");
            BigDecimal cost = rs.getBigDecimal("original_cost");
            BigDecimal price = rs.getBigDecimal("price");

            if (cost == null) cost = BigDecimal.ZERO;
            if (price == null) price = BigDecimal.ZERO;

            int opening = rs.getInt("opening_stock");
            int importQty = rs.getInt("import_qty");
            int deductQty = rs.getInt("order_deduct_qty");
            int cancelQty = rs.getInt("cancel_restore_qty");
            int returnQty = rs.getInt("return_restore_qty");
            int closing = rs.getInt("closing_stock");

            BigDecimal openingCostVal = cost.multiply(BigDecimal.valueOf(opening));
            BigDecimal closingCostVal = cost.multiply(BigDecimal.valueOf(closing));
            BigDecimal closingRetailVal = price.multiply(BigDecimal.valueOf(closing));

            return StockMovementReportItemDto.builder()
                    .productId(productId)
                    .barcode(barcode)
                    .title(title)
                    .categoryName(catName)
                    .productType(productType)
                    .isActive(isActive)
                    .originalCost(cost)
                    .price(price)
                    .openingStock(opening)
                    .importQty(importQty)
                    .orderDeductQty(deductQty)
                    .cancelRestoreQty(cancelQty)
                    .returnRestoreQty(returnQty)
                    .closingStock(closing)
                    .openingCostValue(openingCostVal)
                    .closingCostValue(closingCostVal)
                    .closingRetailValue(closingRetailVal)
                    .build();
        });
    }
}
