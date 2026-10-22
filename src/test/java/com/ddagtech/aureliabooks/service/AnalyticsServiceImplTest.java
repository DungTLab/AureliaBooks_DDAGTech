package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.AnalyticsRepository;
import com.ddagtech.aureliabooks.service.impl.AnalyticsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UC26, UC27: AnalyticsService Unit Tests")
class AnalyticsServiceImplTest {

    @Mock
    private AnalyticsRepository analyticsRepository;

    @InjectMocks
    private AnalyticsServiceImpl analyticsService;

    @Test
    @DisplayName("getRevenueReport - Happy path with delivered orders and KPI calculations")
    void getRevenueReport_happyPath() {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 3))
                .preset("custom")
                .build();

        RevenueSummaryKpiDto kpiFromRepo = RevenueSummaryKpiDto.builder()
                .totalOrders(20)
                .grossRevenue(BigDecimal.valueOf(6000000))
                .totalShippingFee(BigDecimal.valueOf(300000))
                .build();

        DailyRevenueDto day2 = DailyRevenueDto.builder()
                .date(LocalDate.of(2026, 10, 2))
                .orderCount(20)
                .revenue(BigDecimal.valueOf(6000000))
                .build();

        TopProductSaleDto book1 = TopProductSaleDto.builder()
                .productId(101L)
                .barcode("8935244889911")
                .title("Nhà Giả Kim")
                .productType(Product.ProductType.BOOK)
                .totalQuantitySold(15)
                .totalRevenue(BigDecimal.valueOf(1500000))
                .build();

        PaymentBreakdownDto payment = PaymentBreakdownDto.builder()
                .codOrdersCount(10)
                .codRevenue(BigDecimal.valueOf(3000000))
                .vnpayOrdersCount(10)
                .vnpayRevenue(BigDecimal.valueOf(3000000))
                .electronicSharePercent(50.0)
                .build();

        RevenueOrderSummaryDto order1 = RevenueOrderSummaryDto.builder()
                .orderCode("ORD-20261002-001")
                .recipientName("Nguyễn Văn A")
                .phone("0901234567")
                .paymentMethod(Order.PaymentMethod.COD)
                .createdAt(LocalDateTime.of(2026, 10, 2, 10, 30))
                .deliveredAt(LocalDateTime.of(2026, 10, 2, 15, 0))
                .shippingFee(BigDecimal.valueOf(15000))
                .finalTotalAmount(BigDecimal.valueOf(300000))
                .build();

        when(analyticsRepository.queryRevenueSummaryKpis(any(), any())).thenReturn(kpiFromRepo);
        when(analyticsRepository.queryDailyRevenueTrends(any(), any())).thenReturn(List.of(day2));
        when(analyticsRepository.queryTopSellingProducts(eq(Product.ProductType.BOOK), eq(5), any(), any()))
                .thenReturn(List.of(book1));
        when(analyticsRepository.queryTopSellingProducts(eq(Product.ProductType.STATIONERY), eq(5), any(), any()))
                .thenReturn(Collections.emptyList());
        when(analyticsRepository.queryPaymentMethodBreakdown(any(), any())).thenReturn(payment);
        when(analyticsRepository.queryDeliveredOrders(any(), any(), anyInt())).thenReturn(List.of(order1));

        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);

        assertThat(report).isNotNull();
        assertThat(report.getKpi().getTotalOrders()).isEqualTo(20);
        assertThat(report.getKpi().getGrossRevenue()).isEqualByComparingTo(BigDecimal.valueOf(6000000));
        // AOV = 6,000,000 / 20 = 300,000
        assertThat(report.getKpi().getAverageOrderValue()).isEqualByComparingTo(BigDecimal.valueOf(300000));

        // Zero-filling verification: Oct 1, 2, 3 -> 3 days in sequence
        assertThat(report.getDailyTrends()).hasSize(3);
        assertThat(report.getDailyTrends().get(0).getDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(report.getDailyTrends().get(0).getOrderCount()).isEqualTo(0L);
        assertThat(report.getDailyTrends().get(0).getRevenue()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(report.getDailyTrends().get(1).getDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(report.getDailyTrends().get(1).getOrderCount()).isEqualTo(20L);
        assertThat(report.getDailyTrends().get(1).getRevenue()).isEqualByComparingTo(BigDecimal.valueOf(6000000));

        assertThat(report.getDailyTrends().get(2).getDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(report.getDailyTrends().get(2).getOrderCount()).isEqualTo(0L);

        // Chart serialized lists
        assertThat(report.getChartDailyLabels()).hasSize(3);
        assertThat(report.getChartDailyRevenues()).hasSize(3);
        assertThat(report.getChartDailyOrderCounts()).hasSize(3);

        // Top products and orders
        assertThat(report.getTopBooks()).hasSize(1);
        assertThat(report.getTopStationeries()).isEmpty();
        assertThat(report.getRecentDeliveredOrders()).hasSize(1);
        assertThat(report.getPaymentBreakdown().getElectronicSharePercent()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("getRevenueReport - Zero orders in range, safeguards AOV division by zero and null safety")
    void getRevenueReport_zeroOrders_noNpe_aovIsZero() {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2026, 11, 1))
                .endDate(LocalDate.of(2026, 11, 2))
                .preset("custom")
                .build();

        when(analyticsRepository.queryRevenueSummaryKpis(any(), any()))
                .thenReturn(new RevenueSummaryKpiDto());
        when(analyticsRepository.queryDailyRevenueTrends(any(), any()))
                .thenReturn(Collections.emptyList());
        when(analyticsRepository.queryTopSellingProducts(any(), anyInt(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(analyticsRepository.queryPaymentMethodBreakdown(any(), any()))
                .thenReturn(new PaymentBreakdownDto());
        when(analyticsRepository.queryDeliveredOrders(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);

        assertThat(report).isNotNull();
        assertThat(report.getKpi().getTotalOrders()).isEqualTo(0L);
        assertThat(report.getKpi().getGrossRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getKpi().getAverageOrderValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getDailyTrends()).hasSize(2); // Nov 1 and Nov 2
        assertThat(report.getTopBooks()).isEmpty();
        assertThat(report.getTopStationeries()).isEmpty();
        assertThat(report.getRecentDeliveredOrders()).isEmpty();
    }

    @Test
    @DisplayName("getRevenueReport - Handles null filterRequest gracefully by falling back to default dates")
    void getRevenueReport_nullFilter_usesDefault() {
        when(analyticsRepository.queryRevenueSummaryKpis(any(), any()))
                .thenReturn(new RevenueSummaryKpiDto());
        when(analyticsRepository.queryDailyRevenueTrends(any(), any()))
                .thenReturn(Collections.emptyList());
        when(analyticsRepository.queryTopSellingProducts(any(), anyInt(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(analyticsRepository.queryPaymentMethodBreakdown(any(), any()))
                .thenReturn(new PaymentBreakdownDto());
        when(analyticsRepository.queryDeliveredOrders(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(null);

        assertThat(report).isNotNull();
        assertThat(report.getFilter()).isNotNull();
        assertThat(report.getFilter().getStartDate()).isNotNull();
        assertThat(report.getFilter().getEndDate()).isNotNull();
    }

    @Test
    @DisplayName("getStockValuationReport - Returns stock items mapped from repository")
    void getStockValuationReport_happyPath() {
        StockValuationItemDto item1 = StockValuationItemDto.builder()
                .productId(1L)
                .barcode("8930001")
                .title("Sách Giáo Khoa Lớp 10")
                .categoryName("Sách Giáo Khoa")
                .productType(Product.ProductType.BOOK)
                .stockQuantity(100)
                .originalCost(BigDecimal.valueOf(20000))
                .price(BigDecimal.valueOf(30000))
                .totalCostValue(BigDecimal.valueOf(2000000))
                .totalRetailValue(BigDecimal.valueOf(3000000))
                .stockStatus("ĐỦ HÀNG")
                .agingTier("HEALTHY")
                .build();

        when(analyticsRepository.queryStockValuationReport()).thenReturn(List.of(item1));

        List<StockValuationItemDto> results = analyticsService.getStockValuationReport();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getBarcode()).isEqualTo("8930001");
        assertThat(results.get(0).getTotalCostValue()).isEqualByComparingTo(BigDecimal.valueOf(2000000));
        verify(analyticsRepository).queryStockValuationReport();
    }
}
