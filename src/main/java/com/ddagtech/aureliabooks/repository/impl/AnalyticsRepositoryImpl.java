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
    public RevenueSummaryKpiDto queryRevenueSummaryKpis(LocalDateTime startDateTime, LocalDateTime endDateTime) {
        String sql = """
                SELECT 
                    COUNT(o.id) AS total_orders,
                    COALESCE(SUM(o.final_total_amount), 0.00) AS gross_revenue,
                    COALESCE(SUM(o.shipping_fee), 0.00) AS total_shipping_fee,
                    COALESCE(AVG(o.final_total_amount), 0.00) AS average_order_value
                FROM orders o
                WHERE o.order_status = 'DELIVERED'
                  AND (:startDateTime IS NULL OR o.created_at >= :startDateTime)
                  AND (:endDateTime IS NULL OR o.created_at <= :endDateTime)
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDateTime", startDateTime)
                .addValue("endDateTime", endDateTime);

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
    public List<DailyRevenueDto> queryDailyRevenueTrends(LocalDateTime startDateTime, LocalDateTime endDateTime) {
        String sql = """
                SELECT 
                    CAST(o.created_at AS DATE) AS order_date,
                    COUNT(o.id) AS daily_order_count,
                    COALESCE(SUM(o.final_total_amount), 0.00) AS daily_revenue
                FROM orders o
                WHERE o.order_status = 'DELIVERED'
                  AND (:startDateTime IS NULL OR o.created_at >= :startDateTime)
                  AND (:endDateTime IS NULL OR o.created_at <= :endDateTime)
                GROUP BY CAST(o.created_at AS DATE)
                ORDER BY order_date ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDateTime", startDateTime)
                .addValue("endDateTime", endDateTime);

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
    public PaymentBreakdownDto queryPaymentMethodBreakdown(LocalDateTime startDateTime, LocalDateTime endDateTime) {
        String sql = """
                SELECT 
                    o.payment_method,
                    COUNT(o.id) AS transaction_count,
                    COALESCE(SUM(o.final_total_amount), 0.00) AS channel_revenue
                FROM orders o
                WHERE o.order_status = 'DELIVERED'
                  AND (:startDateTime IS NULL OR o.created_at >= :startDateTime)
                  AND (:endDateTime IS NULL OR o.created_at <= :endDateTime)
                GROUP BY o.payment_method
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDateTime", startDateTime)
                .addValue("endDateTime", endDateTime);

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
                                                           LocalDateTime startDateTime, LocalDateTime endDateTime) {
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
                  AND (:endDateTime IS NULL OR o.created_at <= :endDateTime)
                GROUP BY p.id, p.barcode, p.title, p.product_type, p.main_image_url
                ORDER BY total_quantity_sold DESC, total_revenue DESC
                LIMIT :limit
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("productType", productType.name())
                .addValue("limit", limit > 0 ? limit : 5)
                .addValue("startDateTime", startDateTime)
                .addValue("endDateTime", endDateTime);

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
    public List<RevenueOrderSummaryDto> queryDeliveredOrders(LocalDateTime startDateTime, LocalDateTime endDateTime, int limit) {
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
                  AND (:endDateTime IS NULL OR o.created_at <= :endDateTime)
                ORDER BY o.created_at DESC
                LIMIT :limit
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startDateTime", startDateTime)
                .addValue("endDateTime", endDateTime)
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
    public List<StockValuationItemDto> queryStockValuationReport() {
        String sql = """
                SELECT 
                    p.id AS product_id,
                    p.barcode,
                    p.title,
                    c.name AS category_name,
                    p.product_type,
                    p.stock_quantity,
                    p.original_cost,
                    p.price,
                    (p.stock_quantity * p.original_cost) AS total_cost_value,
                    (p.stock_quantity * p.price) AS total_retail_value,
                    p.created_at
                FROM products p
                LEFT JOIN categories c ON p.category_id = c.id
                WHERE p.is_active = TRUE
                ORDER BY total_cost_value DESC
                """;

        LocalDate today = LocalDate.now();

        return jdbc.query(sql, (rs, rowNum) -> {
            int stock = rs.getInt("stock_quantity");
            BigDecimal cost = rs.getBigDecimal("original_cost");
            BigDecimal price = rs.getBigDecimal("price");
            BigDecimal totalCost = rs.getBigDecimal("total_cost_value");
            BigDecimal totalRetail = rs.getBigDecimal("total_retail_value");

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
}
