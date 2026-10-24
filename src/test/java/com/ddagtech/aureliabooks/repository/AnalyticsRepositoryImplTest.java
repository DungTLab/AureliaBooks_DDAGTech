package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.impl.AnalyticsRepositoryImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UC26, UC27: AnalyticsRepository Unit Tests")
class AnalyticsRepositoryImplTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @InjectMocks
    private AnalyticsRepositoryImpl repository;

    @Test
    @DisplayName("queryRevenueSummaryKpis enforces DELIVERED and PAID status (F08) and half-open boundary (F02)")
    void queryRevenueSummaryKpis_happyPath() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime endExclusive = LocalDateTime.of(2026, 10, 22, 0, 0);

        RevenueSummaryKpiDto expectedKpi = RevenueSummaryKpiDto.builder()
                .totalOrders(15)
                .grossRevenue(BigDecimal.valueOf(4500000))
                .build();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        when(jdbc.queryForObject(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class)))
                .thenReturn(expectedKpi);

        RevenueSummaryKpiDto actual = repository.queryRevenueSummaryKpis(start, endExclusive);

        assertThat(actual.getTotalOrders()).isEqualTo(15);
        assertThat(sqlCaptor.getValue()).contains("o.order_status = 'DELIVERED'");
        assertThat(sqlCaptor.getValue()).contains("o.payment_status = 'PAID'");
        assertThat(sqlCaptor.getValue()).contains("o.created_at < :endExclusiveDateTime");
        assertThat(paramsCaptor.getValue().getValue("startDateTime")).isEqualTo(start);
        assertThat(paramsCaptor.getValue().getValue("endExclusiveDateTime")).isEqualTo(endExclusive);
    }

    @Test
    @DisplayName("queryDailyRevenueTrends groups by date and orders ascending, enforcing DELIVERED + PAID (F08)")
    void queryDailyRevenueTrends() {
        DailyRevenueDto day1 = DailyRevenueDto.builder()
                .orderCount(3)
                .revenue(BigDecimal.valueOf(600000))
                .build();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(day1));

        List<DailyRevenueDto> results = repository.queryDailyRevenueTrends(LocalDateTime.now(), LocalDateTime.now());

        assertThat(results).hasSize(1);
        assertThat(sqlCaptor.getValue()).contains("o.order_status = 'DELIVERED'");
        assertThat(sqlCaptor.getValue()).contains("o.payment_status = 'PAID'");
        assertThat(sqlCaptor.getValue()).contains("GROUP BY");
        assertThat(sqlCaptor.getValue()).contains("o.created_at < :endExclusiveDateTime");
    }

    @Test
    @DisplayName("queryMonthlyRevenueTrends groups by month YYYY-MM and orders ascending (F06, F08)")
    void queryMonthlyRevenueTrends() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(new DailyRevenueDto()));

        List<DailyRevenueDto> results = repository.queryMonthlyRevenueTrends(LocalDateTime.now(), LocalDateTime.now());

        assertThat(results).hasSize(1);
        assertThat(sqlCaptor.getValue()).contains("DATE_FORMAT(o.created_at, '%Y-%m')");
        assertThat(sqlCaptor.getValue()).contains("o.order_status = 'DELIVERED'");
        assertThat(sqlCaptor.getValue()).contains("o.payment_status = 'PAID'");
        assertThat(sqlCaptor.getValue()).contains("o.created_at < :endExclusiveDateTime");
    }

    @Test
    @DisplayName("queryPaymentMethodBreakdown queries payment breakdown with exclusive boundary (F08)")
    void queryPaymentMethodBreakdown() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("payment_method")).thenReturn("COD", "VNPAY");
            when(rs.getLong("transaction_count")).thenReturn(10L, 10L);
            when(rs.getBigDecimal("channel_revenue")).thenReturn(BigDecimal.valueOf(1000000), BigDecimal.valueOf(1000000));
            handler.processRow(rs);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        PaymentBreakdownDto breakdown = repository.queryPaymentMethodBreakdown(LocalDateTime.now(), LocalDateTime.now());

        assertThat(breakdown.getCodOrdersCount()).isEqualTo(10L);
        assertThat(breakdown.getVnpayOrdersCount()).isEqualTo(10L);
        assertThat(breakdown.getElectronicSharePercent()).isEqualTo(50.0);
        assertThat(sqlCaptor.getValue()).contains("o.order_status = 'DELIVERED'");
        assertThat(sqlCaptor.getValue()).contains("o.payment_status = 'PAID'");
    }

    @Test
    @DisplayName("F07 Verification: BO-4 electronic payment share calculated strictly by order count")
    void queryPaymentMethodBreakdown_bo4ByOrderCount_F07() {
        // Review fixture: 1 VNPay order (5,000,000đ) and 9 COD orders (900,000đ)
        // Order count share: 1 / 10 = 10.0% (fails >= 40% target)
        // If calculated by revenue: 5,000,000 / 5,900,000 = 84.75% (erroneously passed)
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("payment_method")).thenReturn("COD", "VNPAY");
            when(rs.getLong("transaction_count")).thenReturn(9L, 1L);
            when(rs.getBigDecimal("channel_revenue")).thenReturn(BigDecimal.valueOf(900000), BigDecimal.valueOf(5000000));
            handler.processRow(rs);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        PaymentBreakdownDto breakdown = repository.queryPaymentMethodBreakdown(LocalDateTime.now(), LocalDateTime.now());

        assertThat(breakdown.getCodOrdersCount()).isEqualTo(9L);
        assertThat(breakdown.getVnpayOrdersCount()).isEqualTo(1L);
        assertThat(breakdown.getCodRevenue()).isEqualByComparingTo(BigDecimal.valueOf(900000));
        assertThat(breakdown.getVnpayRevenue()).isEqualByComparingTo(BigDecimal.valueOf(5000000));
        assertThat(breakdown.getElectronicSharePercent()).isEqualTo(10.0);
        assertThat(breakdown.isMeetingElectronicTarget()).isFalse();
    }

    @Test
    @DisplayName("queryTopSellingProducts filters by product type and order status DELIVERED + PAID (F08)")
    void queryTopSellingProducts() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        when(jdbc.query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class)))
                .thenReturn(List.of(new TopProductSaleDto()));

        List<TopProductSaleDto> list = repository.queryTopSellingProducts(Product.ProductType.BOOK, 5, null, null);

        assertThat(list).hasSize(1);
        assertThat(sqlCaptor.getValue()).contains("o.order_status = 'DELIVERED'");
        assertThat(sqlCaptor.getValue()).contains("o.payment_status = 'PAID'");
        assertThat(paramsCaptor.getValue().getValue("productType")).isEqualTo("BOOK");
        assertThat(paramsCaptor.getValue().getValue("limit")).isEqualTo(5);
    }

    @Test
    @DisplayName("queryDeliveredOrders queries only DELIVERED + PAID orders sorted by created_at desc with limit (F08)")
    void queryDeliveredOrders() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(new RevenueOrderSummaryDto()));

        List<RevenueOrderSummaryDto> orders = repository.queryDeliveredOrders(null, null, 50);

        assertThat(orders).hasSize(1);
        assertThat(sqlCaptor.getValue()).contains("o.order_status = 'DELIVERED'");
        assertThat(sqlCaptor.getValue()).contains("o.payment_status = 'PAID'");
        assertThat(sqlCaptor.getValue()).contains("ORDER BY o.created_at DESC");
        assertThat(sqlCaptor.getValue()).contains("LIMIT :limit");
    }

    @Test
    @DisplayName("queryAllDeliveredOrders queries all DELIVERED + PAID orders without limit for full export (F03, F08)")
    void queryAllDeliveredOrders() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(new RevenueOrderSummaryDto()));

        List<RevenueOrderSummaryDto> orders = repository.queryAllDeliveredOrders(null, null);

        assertThat(orders).hasSize(1);
        assertThat(sqlCaptor.getValue()).contains("o.order_status = 'DELIVERED'");
        assertThat(sqlCaptor.getValue()).contains("o.payment_status = 'PAID'");
        assertThat(sqlCaptor.getValue()).contains("ORDER BY o.created_at DESC");
        assertThat(sqlCaptor.getValue()).doesNotContain("LIMIT");
    }

    @Test
    @DisplayName("queryStockValuationReport includes inactive products if they have remaining stock (F05)")
    void queryStockValuationReport() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sqlCaptor.capture(), any(RowMapper.class)))
                .thenReturn(List.of(new StockValuationItemDto()));

        List<StockValuationItemDto> stock = repository.queryStockValuationReport();

        assertThat(stock).hasSize(1);
        assertThat(sqlCaptor.getValue()).contains("WHERE (p.is_active = TRUE OR p.stock_quantity > 0)");
    }

    @Test
    @DisplayName("queryPeriodStockMovementReport aggregates from inventory_movements view (F04)")
    void queryPeriodStockMovementReport() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        when(jdbc.query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class)))
                .thenReturn(List.of(new StockMovementReportItemDto()));

        LocalDateTime fromInclusive = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime toExclusive = LocalDateTime.of(2026, 11, 1, 0, 0);

        List<StockMovementReportItemDto> items = repository.queryPeriodStockMovementReport(fromInclusive, toExclusive);

        assertThat(items).hasSize(1);
        assertThat(sqlCaptor.getValue()).contains("FROM inventory_movements");
        assertThat(sqlCaptor.getValue()).contains("movement_type = 'IMPORT'");
        assertThat(sqlCaptor.getValue()).contains("movement_type = 'ORDER_DEDUCT'");
        assertThat(sqlCaptor.getValue()).contains("movement_type = 'ORDER_CANCELLED_RESTOCK'");
        assertThat(sqlCaptor.getValue()).contains("movement_type = 'ORDER_RETURNED_RESTOCK'");
        assertThat(paramsCaptor.getValue().getValue("fromInclusive")).isEqualTo(fromInclusive);
        assertThat(paramsCaptor.getValue().getValue("toExclusive")).isEqualTo(toExclusive);
    }
}
