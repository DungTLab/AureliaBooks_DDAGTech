package com.ddagtech.aureliabooks.util;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.service.impl.ExcelExportServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UC27: Apache POI Excel Export Engine Unit Tests")
class ExcelExportUtilTest {

    private final ExcelExportServiceImpl excelExportService = new ExcelExportServiceImpl();

    @Test
    @DisplayName("exportRevenueExcel - Generates 3 well-formed sheets with correct headers and styles")
    void exportRevenueExcel_happyPath() throws IOException {
        RevenueFilterRequest filter = RevenueFilterRequest.builder()
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 5))
                .preset("custom")
                .build();

        RevenueSummaryKpiDto kpi = RevenueSummaryKpiDto.builder()
                .totalOrders(10)
                .grossRevenue(BigDecimal.valueOf(5000000))
                .totalShippingFee(BigDecimal.valueOf(150000))
                .averageOrderValue(BigDecimal.valueOf(500000))
                .build();

        DailyRevenueDto day1 = DailyRevenueDto.builder()
                .date(LocalDate.of(2026, 10, 1))
                .orderCount(5)
                .revenue(BigDecimal.valueOf(2500000))
                .build();
        DailyRevenueDto day2 = DailyRevenueDto.builder()
                .date(LocalDate.of(2026, 10, 2))
                .orderCount(5)
                .revenue(BigDecimal.valueOf(2500000))
                .build();

        TopProductSaleDto book = TopProductSaleDto.builder()
                .productId(101L)
                .barcode("8935244889911")
                .title("Đắc Nhân Tâm")
                .productType(Product.ProductType.BOOK)
                .totalQuantitySold(10)
                .totalRevenue(BigDecimal.valueOf(1000000))
                .build();

        TopProductSaleDto stat = TopProductSaleDto.builder()
                .productId(202L)
                .barcode("8935001122334")
                .title("Bút Bi Thiên Long FO-03")
                .productType(Product.ProductType.STATIONERY)
                .totalQuantitySold(50)
                .totalRevenue(BigDecimal.valueOf(250000))
                .build();

        RevenueOrderSummaryDto order = RevenueOrderSummaryDto.builder()
                .orderCode("ORD-20261001-001")
                .recipientName("Trần Văn B")
                .phone("0987654321")
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .createdAt(LocalDateTime.of(2026, 10, 1, 9, 15))
                .deliveredAt(LocalDateTime.of(2026, 10, 1, 14, 30))
                .shippingFee(BigDecimal.valueOf(15000))
                .finalTotalAmount(BigDecimal.valueOf(500000))
                .build();

        RevenueAnalyticsReportDto reportDto = RevenueAnalyticsReportDto.builder()
                .filter(filter)
                .kpi(kpi)
                .dailyTrends(List.of(day1, day2))
                .topBooks(List.of(book))
                .topStationeries(List.of(stat))
                .recentDeliveredOrders(List.of(order))
                .paymentBreakdown(PaymentBreakdownDto.builder().electronicSharePercent(100.0).build())
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        excelExportService.exportRevenueExcel(reportDto, baos);

        byte[] bytes = baos.toByteArray();
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook parsedWb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(parsedWb.getNumberOfSheets()).isEqualTo(3);

            // Sheet 1: Tổng quan & Doanh thu theo ngày
            Sheet sheet1 = parsedWb.getSheetAt(0);
            assertThat(sheet1.getSheetName()).contains("Tổng quan");
            assertThat(sheet1.getRow(0).getCell(0).getStringCellValue()).contains("BÁO CÁO DOANH THU");

            // Sheet 2: Top Sản phẩm Bán chạy
            Sheet sheet2 = parsedWb.getSheetAt(1);
            assertThat(sheet2.getSheetName()).contains("Top");

            // Sheet 3: Danh sách Đơn hàng Hoàn tất
            Sheet sheet3 = parsedWb.getSheetAt(2);
            assertThat(sheet3.getSheetName()).contains("Đơn hàng");
            assertThat(sheet3.getRow(0).getCell(0).getStringCellValue()).contains("DANH SÁCH ĐƠN HÀNG");
        }
    }

    @Test
    @DisplayName("exportRevenueExcel - Handles empty dataset without throwing NPE or corrupting workbook")
    void exportRevenueExcel_emptyData_createsHeadersWithoutCrashing() throws IOException {
        RevenueAnalyticsReportDto reportDto = new RevenueAnalyticsReportDto();
        reportDto.getKpi().setTotalOrders(0);
        reportDto.getKpi().setGrossRevenue(BigDecimal.ZERO);
        reportDto.getKpi().setAverageOrderValue(BigDecimal.ZERO);
        reportDto.getKpi().setTotalShippingFee(BigDecimal.ZERO);
        reportDto.setDailyTrends(Collections.emptyList());
        reportDto.setTopBooks(Collections.emptyList());
        reportDto.setTopStationeries(Collections.emptyList());
        reportDto.setRecentDeliveredOrders(Collections.emptyList());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        excelExportService.exportRevenueExcel(reportDto, baos);

        byte[] bytes = baos.toByteArray();
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook parsedWb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(parsedWb.getNumberOfSheets()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("exportStockValuationExcel - Generates stock valuation sheet with formatted columns")
    void exportStockValuationExcel_happyPath() throws IOException {
        StockValuationItemDto item = StockValuationItemDto.builder()
                .productId(1L)
                .barcode("8930001")
                .title("Bút Máy Cao Cấp")
                .categoryName("Văn phòng phẩm")
                .productType(Product.ProductType.STATIONERY)
                .stockQuantity(25)
                .originalCost(BigDecimal.valueOf(80000))
                .price(BigDecimal.valueOf(120000))
                .totalCostValue(BigDecimal.valueOf(2000000))
                .totalRetailValue(BigDecimal.valueOf(3000000))
                .stockStatus("ĐỦ HÀNG")
                .agingTier("HEALTHY")
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        excelExportService.exportStockValuationExcel(List.of(item), baos);

        byte[] bytes = baos.toByteArray();
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook parsedWb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(parsedWb.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = parsedWb.getSheetAt(0);
            assertThat(sheet.getSheetName()).contains("Tồn kho");

            // Row 0 is Title
            Row titleRow = sheet.getRow(0);
            assertThat(titleRow.getCell(0).getStringCellValue()).contains("BÁO CÁO TỒN KHO & ĐỊNH GIÁ");

            // Row 3 is Header
            Row headerRow = sheet.getRow(3);
            assertThat(headerRow.getCell(0).getStringCellValue()).isEqualTo("STT");
            assertThat(headerRow.getCell(2).getStringCellValue()).isEqualTo("Mã vạch (Barcode)");
            assertThat(headerRow.getCell(3).getStringCellValue()).isEqualTo("Tên sản phẩm");

            // Row 4 is Data row
            Row dataRow = sheet.getRow(4);
            assertThat(dataRow.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(dataRow.getCell(2).getStringCellValue()).isEqualTo("8930001");
            assertThat(dataRow.getCell(3).getStringCellValue()).isEqualTo("Bút Máy Cao Cấp");
        }
    }
}
