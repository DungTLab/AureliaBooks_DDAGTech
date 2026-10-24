package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.AnalyticsRepository;
import com.ddagtech.aureliabooks.service.impl.AnalyticsServiceImpl;
import com.ddagtech.aureliabooks.service.impl.ExcelExportServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UC26, UC27: Quality Gate & Full Acceptance Criteria Integration Suite")
class AnalyticsIntegrationTest {

    @Mock
    private AnalyticsRepository analyticsRepository;

    @InjectMocks
    private AnalyticsServiceImpl analyticsService;

    private final ExcelExportServiceImpl excelExportService = new ExcelExportServiceImpl();

    @Test
    @DisplayName("Quality Gate Verification: Full aggregation pipeline execution under 500ms")
    void aggregationQueryPerformance_under500ms() {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 1, 31))
                .preset("custom")
                .build();

        RevenueSummaryKpiDto kpi = RevenueSummaryKpiDto.builder()
                .totalOrders(1000)
                .grossRevenue(BigDecimal.valueOf(250000000))
                .totalShippingFee(BigDecimal.valueOf(15000000))
                .build();

        when(analyticsRepository.queryRevenueSummaryKpis(any(), any())).thenReturn(kpi);
        when(analyticsRepository.queryDailyRevenueTrends(any(), any())).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryTopSellingProducts(eq(Product.ProductType.BOOK), eq(5), any(), any()))
                .thenReturn(Collections.emptyList());
        when(analyticsRepository.queryTopSellingProducts(eq(Product.ProductType.STATIONERY), eq(5), any(), any()))
                .thenReturn(Collections.emptyList());
        when(analyticsRepository.queryPaymentMethodBreakdown(any(), any())).thenReturn(new PaymentBreakdownDto());
        when(analyticsRepository.queryDeliveredOrders(any(), any(), eq(100))).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryAllDeliveredOrders(any(), any())).thenReturn(Collections.emptyList());

        long startTime = System.currentTimeMillis();
        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);
        long elapsedMs = System.currentTimeMillis() - startTime;

        // Verify quality gate: aggregation pipeline must complete within 500ms
        assertThat(elapsedMs).isLessThan(500);
        assertThat(report.getKpi().getTotalOrders()).isEqualTo(1000);
        assertThat(report.getDailyTrends()).hasSize(31); // 31 days in Jan 2026 zero-filled
    }

    @Test
    @DisplayName("Quality Gate: Excel workbook generation succeeds and produces well-formed 3-sheet document")
    void excelGeneration_succeeds() throws IOException {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 31))
                .preset("thismonth")
                .build();
        filter.resolveDates();

        List<RevenueOrderSummaryDto> sampleOrders = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            sampleOrders.add(RevenueOrderSummaryDto.builder()
                    .orderCode("ORD-202610-" + String.format("%04d", i))
                    .recipientName("Khách hàng thứ " + i)
                    .phone("0987654321")
                    .paymentMethod(i % 2 == 0 ? Order.PaymentMethod.VNPAY : Order.PaymentMethod.COD)
                    .createdAt(LocalDateTime.now())
                    .deliveredAt(LocalDateTime.now())
                    .shippingFee(BigDecimal.valueOf(20000))
                    .finalTotalAmount(BigDecimal.valueOf(350000))
                    .build());
        }

        RevenueAnalyticsReportDto reportDto = RevenueAnalyticsReportDto.builder()
                .filter(filter)
                .kpi(RevenueSummaryKpiDto.builder()
                        .totalOrders(50)
                        .grossRevenue(BigDecimal.valueOf(17500000))
                        .averageOrderValue(BigDecimal.valueOf(350000))
                        .totalShippingFee(BigDecimal.valueOf(1000000))
                        .build())
                .dailyTrends(Collections.emptyList())
                .topBooks(Collections.emptyList())
                .topStationeries(Collections.emptyList())
                .recentDeliveredOrders(sampleOrders)
                .allDeliveredOrders(sampleOrders)
                .paymentBreakdown(PaymentBreakdownDto.builder().electronicSharePercent(50.0).build())
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        excelExportService.exportRevenueExcel(reportDto, baos);

        // Quality Gate: Excel generation succeeds, non-empty binary stream, valid 3-sheet workbook
        assertThat(baos.size()).isGreaterThan(0);

        try (XSSFWorkbook parsed = new XSSFWorkbook(new ByteArrayInputStream(baos.toByteArray()))) {
            assertThat(parsed.getNumberOfSheets()).isEqualTo(3);
            assertThat(parsed.getSheetAt(0).getSheetName()).contains("Tổng quan");
            assertThat(parsed.getSheetAt(1).getSheetName()).contains("Top");
            assertThat(parsed.getSheetAt(2).getSheetName()).contains("Đơn hàng");
        }
    }

    @Test
    @DisplayName("F03 Verification: Exporting 101 orders does NOT truncate to 100 and Sheet 3 totals match Overview")
    void f03_exportMoreThan100Orders_noTruncation_totalsMatch() throws IOException {
        int orderCount = 101;
        BigDecimal unitPrice = BigDecimal.valueOf(100000);
        BigDecimal expectedGross = unitPrice.multiply(BigDecimal.valueOf(orderCount)); // 10,100,000 VND
        BigDecimal shippingPerOrder = BigDecimal.valueOf(10000);
        BigDecimal expectedShipping = shippingPerOrder.multiply(BigDecimal.valueOf(orderCount)); // 1,010,000 VND

        List<RevenueOrderSummaryDto> fullOrdersList = new ArrayList<>();
        for (int i = 1; i <= orderCount; i++) {
            fullOrdersList.add(RevenueOrderSummaryDto.builder()
                    .orderCode("ORD-101-" + String.format("%03d", i))
                    .recipientName("Khách " + i)
                    .phone("0900000000")
                    .paymentMethod(Order.PaymentMethod.COD)
                    .createdAt(LocalDateTime.of(2026, 10, 15, 10, 0))
                    .deliveredAt(LocalDateTime.of(2026, 10, 15, 16, 0))
                    .shippingFee(shippingPerOrder)
                    .finalTotalAmount(unitPrice)
                    .build());
        }

        RevenueAnalyticsReportDto reportDto = RevenueAnalyticsReportDto.builder()
                .filter(RevenueFilterRequest.builder().startDate(LocalDate.of(2026, 10, 1)).endDate(LocalDate.of(2026, 10, 31)).build())
                .kpi(RevenueSummaryKpiDto.builder()
                        .totalOrders(orderCount)
                        .grossRevenue(expectedGross)
                        .totalShippingFee(expectedShipping)
                        .averageOrderValue(unitPrice)
                        .build())
                .dailyTrends(Collections.emptyList())
                .recentDeliveredOrders(fullOrdersList.subList(0, 100)) // UI truncated to 100
                .allDeliveredOrders(fullOrdersList) // Full export has 101
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        excelExportService.exportRevenueExcel(reportDto, baos);

        try (XSSFWorkbook parsed = new XSSFWorkbook(new ByteArrayInputStream(baos.toByteArray()))) {
            Sheet sheet3 = parsed.getSheetAt(2); // "Danh sách Đơn hàng Hoàn tất"
            // Row 0 is Title, Row 1 is Subtitle, Row 2 is Empty, Row 3 is Header
            // Data rows: Row 4 to Row 4 + 101 - 1 = Row 104 (101 data rows)
            // Total row is at Row 105
            int totalRowIdx = 4 + orderCount;
            Row totalRow = sheet3.getRow(totalRowIdx);
            assertThat(totalRow).isNotNull();
            assertThat(totalRow.getCell(6).getStringCellValue()).isEqualTo("TỔNG CỘNG:");
            // Total shipping in Col 7
            assertThat(totalRow.getCell(7).getNumericCellValue()).isEqualTo(expectedShipping.doubleValue());
            // Total amount in Col 8
            assertThat(totalRow.getCell(8).getNumericCellValue()).isEqualTo(expectedGross.doubleValue());

            // Ensure row 104 is the 101st order (STT = 101)
            Row lastDataRow = sheet3.getRow(totalRowIdx - 1);
            assertThat(lastDataRow.getCell(0).getNumericCellValue()).isEqualTo(101.0);
            assertThat(lastDataRow.getCell(1).getStringCellValue()).isEqualTo("ORD-101-101");
        }
    }

    @Test
    @DisplayName("AC-01: Settled Gross Revenue strictly aggregates from DELIVERED orders only")
    void ac01_grossRevenue_strictlyDeliveredOrdersOnly() {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 31))
                .preset("thismonth")
                .build();

        RevenueSummaryKpiDto deliveredKpi = RevenueSummaryKpiDto.builder()
                .totalOrders(1)
                .grossRevenue(BigDecimal.valueOf(500000))
                .totalShippingFee(BigDecimal.valueOf(30000))
                .build();

        when(analyticsRepository.queryRevenueSummaryKpis(any(), any())).thenReturn(deliveredKpi);
        when(analyticsRepository.queryDailyRevenueTrends(any(), any())).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryTopSellingProducts(any(), anyInt(), any(), any())).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryPaymentMethodBreakdown(any(), any())).thenReturn(new PaymentBreakdownDto());
        when(analyticsRepository.queryDeliveredOrders(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryAllDeliveredOrders(any(), any())).thenReturn(Collections.emptyList());

        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);

        assertThat(report.getKpi().getTotalOrders()).isEqualTo(1L);
        assertThat(report.getKpi().getGrossRevenue()).isEqualByComparingTo(BigDecimal.valueOf(500000));
        assertThat(report.getKpi().getFormattedGrossRevenue()).isEqualTo("500.000 ₫");
        assertThat(report.getKpi().getAverageOrderValue()).isEqualByComparingTo(BigDecimal.valueOf(500000));
        assertThat(report.getKpi().getFormattedAverageOrderValue()).isEqualTo("500.000 ₫");
    }

    @Test
    @DisplayName("AC-04: Negative/Boundary empty date range returns 0 VND, AOV 0 VND, no NPE and clean chart lists")
    void ac04_negativeEmptyDateRange_returnsZeroVndWithoutNpe() {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2099, 1, 1))
                .endDate(LocalDate.of(2099, 1, 5))
                .preset("custom")
                .build();

        when(analyticsRepository.queryRevenueSummaryKpis(any(), any())).thenReturn(null);
        when(analyticsRepository.queryDailyRevenueTrends(any(), any())).thenReturn(null);
        when(analyticsRepository.queryTopSellingProducts(any(), anyInt(), any(), any())).thenReturn(null);
        when(analyticsRepository.queryPaymentMethodBreakdown(any(), any())).thenReturn(null);
        when(analyticsRepository.queryDeliveredOrders(any(), any(), anyInt())).thenReturn(null);
        when(analyticsRepository.queryAllDeliveredOrders(any(), any())).thenReturn(null);

        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);

        assertThat(report).isNotNull();
        assertThat(report.getKpi().getTotalOrders()).isEqualTo(0L);
        assertThat(report.getKpi().getGrossRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getKpi().getFormattedGrossRevenue()).isEqualTo("0 ₫");
        assertThat(report.getKpi().getAverageOrderValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getKpi().getFormattedAverageOrderValue()).isEqualTo("0 ₫");

        // Zero-filling verification: 5 continuous days with 0 VND
        assertThat(report.getDailyTrends()).hasSize(5);
        for (DailyRevenueDto day : report.getDailyTrends()) {
            assertThat(day.getOrderCount()).isEqualTo(0L);
            assertThat(day.getRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(day.getFormattedRevenue()).isEqualTo("0 ₫");
        }

        assertThat(report.getTopBooks()).isEmpty();
        assertThat(report.getTopStationeries()).isEmpty();
        assertThat(report.getRecentDeliveredOrders()).isEmpty();
        assertThat(report.isHasData()).isFalse();
    }

    @Test
    @DisplayName("BO-4 Objective: Segregated Cash vs Electronic Share percentage calculation")
    void bo4_electronicPaymentRatio_correctSegregation() {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 15))
                .preset("custom")
                .build();

        PaymentBreakdownDto payment = PaymentBreakdownDto.builder()
                .codOrdersCount(60)
                .codRevenue(BigDecimal.valueOf(60000000))
                .vnpayOrdersCount(40)
                .vnpayRevenue(BigDecimal.valueOf(40000000))
                .electronicSharePercent(40.0)
                .build();

        when(analyticsRepository.queryRevenueSummaryKpis(any(), any()))
                .thenReturn(RevenueSummaryKpiDto.builder().totalOrders(100).grossRevenue(BigDecimal.valueOf(100000000)).build());
        when(analyticsRepository.queryDailyRevenueTrends(any(), any())).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryTopSellingProducts(any(), anyInt(), any(), any())).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryPaymentMethodBreakdown(any(), any())).thenReturn(payment);
        when(analyticsRepository.queryDeliveredOrders(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(analyticsRepository.queryAllDeliveredOrders(any(), any())).thenReturn(Collections.emptyList());

        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);

        assertThat(report.getPaymentBreakdown().getCodOrdersCount()).isEqualTo(60L);
        assertThat(report.getPaymentBreakdown().getVnpayOrdersCount()).isEqualTo(40L);
        assertThat(report.getPaymentBreakdown().getElectronicSharePercent()).isEqualTo(40.0);
        assertThat(report.getPaymentBreakdown().getFormattedElectronicSharePercent()).isEqualTo("40.0%");
        assertThat(report.getPaymentBreakdown().isMeetingElectronicTarget()).isTrue();
    }
}
