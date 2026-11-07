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
    @DisplayName("exportRevenueExcel - Generates 2 well-formed sheets with correct headers, net profit and styles")
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
            // Updated V2.1 specification: 2 Sheets (Top Bán chạy removed)
            assertThat(parsedWb.getNumberOfSheets()).isEqualTo(2);

            // Sheet 1: Tổng quan & Doanh thu theo ngày
            Sheet sheet1 = parsedWb.getSheetAt(0);
            assertThat(sheet1.getSheetName()).contains("Tổng quan");
            assertThat(sheet1.getRow(0).getCell(0).getStringCellValue()).contains("BÁO CÁO DOANH THU THEO KỲ");

            // Section I: KPI Header at row 4
            Row kpiHeaderRow = sheet1.getRow(4);
            assertThat(kpiHeaderRow.getCell(0).getStringCellValue()).isEqualTo("Chỉ tiêu tài chính");
            assertThat(kpiHeaderRow.getCell(3).getStringCellValue()).isEqualTo("Số liệu ghi nhận");

            // Row 5: KPI Tổng doanh thu (Col 3 is numeric double with custom format, verifying Issue 2)
            Row kpiRow0 = sheet1.getRow(5);
            assertThat(kpiRow0.getCell(0).getStringCellValue()).isEqualTo("Tổng doanh thu");
            assertThat(kpiRow0.getCell(3).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(kpiRow0.getCell(3).getNumericCellValue()).isEqualTo(5000000.0);

            // Daily Header at row 13 (no hardcoded profit column)
            Row dailyHeaderRow = sheet1.getRow(13);
            assertThat(dailyHeaderRow.getCell(0).getStringCellValue()).isEqualTo("STT");
            assertThat(dailyHeaderRow.getCell(1).getStringCellValue()).isEqualTo("Ngày giao dịch");
            assertThat(dailyHeaderRow.getCell(2).getStringCellValue()).isEqualTo("Số đơn hoàn tất");
            assertThat(dailyHeaderRow.getCell(3).getStringCellValue()).isEqualTo("Doanh thu (VND)");
            assertThat(dailyHeaderRow.getCell(4)).isNull();

            // Sheet 2: Danh sách Đơn hàng Hoàn tất
            Sheet sheet2 = parsedWb.getSheetAt(1);
            assertThat(sheet2.getSheetName()).contains("Đơn hàng");
            assertThat(sheet2.getRow(0).getCell(0).getStringCellValue()).contains("DANH SÁCH ĐƠN HÀNG HOÀN TẤT");

            Row orderHeaderRow = sheet2.getRow(3);
            assertThat(orderHeaderRow.getCell(0).getStringCellValue()).isEqualTo("STT");
            assertThat(orderHeaderRow.getCell(1).getStringCellValue()).isEqualTo("Người nhận");
            assertThat(orderHeaderRow.getCell(3).getStringCellValue()).isEqualTo("Phương thức thanh toán");
            assertThat(orderHeaderRow.getCell(7).getStringCellValue()).isEqualTo("Tổng tiền thanh toán (VND)");
            assertThat(orderHeaderRow.getCell(8)).isNull();
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
            assertThat(parsedWb.getNumberOfSheets()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("exportStockValuationExcel - Generates stock valuation sheet with formatted columns and active status (F05)")
    void exportStockValuationExcel_happyPath() throws IOException {
        StockValuationItemDto item = StockValuationItemDto.builder()
                .productId(1L)
                .barcode("8930001")
                .title("Bút Máy Cao Cấp")
                .categoryName("Văn phòng phẩm")
                .productType(Product.ProductType.STATIONERY)
                .isActive(true)
                .stockQuantity(25)
                .originalCost(BigDecimal.valueOf(80000))
                .price(BigDecimal.valueOf(120000))
                .totalCostValue(BigDecimal.valueOf(2000000))
                .totalRetailValue(BigDecimal.valueOf(3000000))
                .stockStatus("Tồn an toàn")
                .agingTier("HEALTHY")
                .build();

        StockValuationItemDto inactiveItem = StockValuationItemDto.builder()
                .productId(2L)
                .barcode("8930002")
                .title("Sách Cũ Tồn")
                .categoryName("Sách")
                .productType(Product.ProductType.BOOK)
                .isActive(false)
                .stockQuantity(7)
                .originalCost(BigDecimal.valueOf(50000))
                .price(BigDecimal.valueOf(70000))
                .totalCostValue(BigDecimal.valueOf(350000))
                .totalRetailValue(BigDecimal.valueOf(490000))
                .stockStatus("Tồn kho thấp")
                .agingTier("DEAD_STOCK")
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        excelExportService.exportStockValuationExcel(List.of(item, inactiveItem), baos);

        byte[] bytes = baos.toByteArray();
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook parsedWb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(parsedWb.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = parsedWb.getSheetAt(0);
            assertThat(sheet.getSheetName()).contains("Tồn kho");

            // Row 0 is Title
            Row titleRow = sheet.getRow(0);
            assertThat(titleRow.getCell(0).getStringCellValue()).contains("BÁO CÁO ĐỊNH GIÁ TÀI SẢN KHO HÀNG");

            // Row 3 is Header
            Row headerRow = sheet.getRow(3);
            assertThat(headerRow.getCell(0).getStringCellValue()).isEqualTo("STT");
            assertThat(headerRow.getCell(1).getStringCellValue()).isEqualTo("Tên sản phẩm");
            assertThat(headerRow.getCell(2).getStringCellValue()).isEqualTo("Phân loại");
            assertThat(headerRow.getCell(4).getStringCellValue()).isEqualTo("Trạng thái KD");

            // Row 4 is Data row 1 (Active)
            Row dataRow1 = sheet.getRow(4);
            assertThat(dataRow1.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(dataRow1.getCell(1).getStringCellValue()).isEqualTo("Bút Máy Cao Cấp");
            assertThat(dataRow1.getCell(4).getStringCellValue()).isEqualTo("Đang kinh doanh");

            // Row 5 is Data row 2 (Inactive item retained with stock)
            Row dataRow2 = sheet.getRow(5);
            assertThat(dataRow2.getCell(0).getNumericCellValue()).isEqualTo(2.0);
            assertThat(dataRow2.getCell(1).getStringCellValue()).isEqualTo("Sách Cũ Tồn");
            assertThat(dataRow2.getCell(4).getStringCellValue()).isEqualTo("Ngừng kinh doanh");

            // Row 6 is Total row: total units = 25 + 7 = 32, total cost = 2,000,000 + 350,000 = 2,350,000
            Row totalRow = sheet.getRow(6);
            assertThat(totalRow.getCell(4).getStringCellValue()).isEqualTo("TỔNG CỘNG TÀI SẢN KHO:");
            assertThat(totalRow.getCell(5).getNumericCellValue()).isEqualTo(32.0);
            assertThat(totalRow.getCell(8).getNumericCellValue()).isEqualTo(2350000.0);
        }
    }

    @Test
    @DisplayName("exportPeriodStockReport - Generates single unified sheet with 15 columns V2.1 (F04)")
    void exportPeriodStockReport_generatesDocumentMovementAndValuationSheets() throws IOException {
        StockMovementFilterRequest filter = StockMovementFilterRequest.builder()
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 31))
                .build();

        StockMovementReportItemDto item = StockMovementReportItemDto.builder()
                .productId(16L)
                .barcode("8936037748888")
                .title("Bút Bi Pilot G2")
                .categoryName("Bút viết")
                .productType(Product.ProductType.STATIONERY)
                .isActive(true)
                .originalCost(BigDecimal.valueOf(25000))
                .price(BigDecimal.valueOf(40000))
                .openingStock(10)
                .importQty(5)
                .orderDeductQty(3)
                .cancelRestoreQty(1)
                .returnRestoreQty(0)
                .closingStock(13) // 10 + 5 + 1 - 3 = 13
                .openingCostValue(BigDecimal.valueOf(250000))
                .closingCostValue(BigDecimal.valueOf(325000))
                .closingRetailValue(BigDecimal.valueOf(520000))
                .build();

        StockValuationItemDto valuationItem = StockValuationItemDto.builder()
                .productId(16L)
                .barcode("8936037748888")
                .title("Bút Bi Pilot G2")
                .categoryName("Bút viết")
                .productType(Product.ProductType.STATIONERY)
                .isActive(true)
                .stockQuantity(13)
                .originalCost(BigDecimal.valueOf(25000))
                .price(BigDecimal.valueOf(40000))
                .totalCostValue(BigDecimal.valueOf(325000))
                .totalRetailValue(BigDecimal.valueOf(520000))
                .stockStatus("Tồn an toàn")
                .build();

        // Item 2: Closing stock = 0 in report period, but real-time valuation has high stock and "ĐỦ HÀNG" (Issue 3 defect test)
        StockMovementReportItemDto itemZeroStock = StockMovementReportItemDto.builder()
                .productId(20L)
                .barcode("8936037749999")
                .title("Sách Bán Hết Trong Kỳ")
                .categoryName("Sách kinh tế")
                .productType(Product.ProductType.BOOK)
                .isActive(true)
                .originalCost(BigDecimal.valueOf(50000))
                .price(BigDecimal.valueOf(100000))
                .openingStock(5)
                .importQty(0)
                .orderDeductQty(5)
                .cancelRestoreQty(0)
                .returnRestoreQty(0)
                .closingStock(0)
                .openingCostValue(BigDecimal.valueOf(250000))
                .closingCostValue(BigDecimal.ZERO)
                .closingRetailValue(BigDecimal.ZERO)
                .build();

        StockValuationItemDto valuationItemZero = StockValuationItemDto.builder()
                .productId(20L)
                .barcode("8936037749999")
                .title("Sách Bán Hết Trong Kỳ")
                .stockQuantity(100)
                .stockStatus("ĐỦ HÀNG")
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        excelExportService.exportPeriodStockReport(filter, List.of(item, itemZeroStock), List.of(valuationItem, valuationItemZero), baos);

        byte[] bytes = baos.toByteArray();
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook parsedWb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            // Single unified sheet V2.1 specification
            assertThat(parsedWb.getNumberOfSheets()).isEqualTo(1);

            Sheet sheet1 = parsedWb.getSheetAt(0);
            assertThat(sheet1.getSheetName()).contains("Biến động");
            assertThat(sheet1.getRow(0).getCell(0).getStringCellValue()).contains("BIẾN ĐỘNG KHO THEO KỲ");

            Row headerRow = sheet1.getRow(3);
            assertThat(headerRow.getCell(0).getStringCellValue()).isEqualTo("STT");
            assertThat(headerRow.getCell(1).getStringCellValue()).isEqualTo("Tên sản phẩm");
            assertThat(headerRow.getCell(2).getStringCellValue()).isEqualTo("Phân loại");
            assertThat(headerRow.getCell(3).getStringCellValue()).isEqualTo("Danh mục");
            assertThat(headerRow.getCell(4).getStringCellValue()).isEqualTo("Giá nhập / SP (VND)");
            assertThat(headerRow.getCell(5).getStringCellValue()).isEqualTo("Giá bán / SP (VND)");
            assertThat(headerRow.getCell(6).getStringCellValue()).isEqualTo("Tồn đầu kỳ (SP)");
            assertThat(headerRow.getCell(7).getStringCellValue()).isEqualTo("Nhập hàng (SP)");
            assertThat(headerRow.getCell(8).getStringCellValue()).isEqualTo("Xuất hàng (SP)");
            assertThat(headerRow.getCell(9).getStringCellValue()).isEqualTo("Hoàn hàng (SP)");
            assertThat(headerRow.getCell(10).getStringCellValue()).isEqualTo("Tồn cuối kỳ (SP)");
            assertThat(headerRow.getCell(11).getStringCellValue()).isEqualTo("Tiền vốn tồn đầu (VND)");
            assertThat(headerRow.getCell(12).getStringCellValue()).isEqualTo("Tiền vốn tồn cuối (VND)");
            assertThat(headerRow.getCell(13).getStringCellValue()).isEqualTo("Tiền bán tồn cuối (VND)");
            assertThat(headerRow.getCell(14).getStringCellValue()).isEqualTo("Tình trạng tồn");

            // Row 4: Pilot G2 (closingStock = 13 -> "Tồn an toàn")
            Row dataRow1 = sheet1.getRow(4);
            assertThat(dataRow1.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(dataRow1.getCell(1).getStringCellValue()).isEqualTo("Bút Bi Pilot G2");
            assertThat(dataRow1.getCell(2).getStringCellValue()).isEqualTo("Văn phòng phẩm");
            assertThat(dataRow1.getCell(6).getNumericCellValue()).isEqualTo(10.0);
            assertThat(dataRow1.getCell(7).getNumericCellValue()).isEqualTo(5.0);
            assertThat(dataRow1.getCell(8).getNumericCellValue()).isEqualTo(3.0);
            assertThat(dataRow1.getCell(9).getNumericCellValue()).isEqualTo(1.0);
            assertThat(dataRow1.getCell(10).getNumericCellValue()).isEqualTo(13.0);
            assertThat(dataRow1.getCell(11).getNumericCellValue()).isEqualTo(250000.0);
            assertThat(dataRow1.getCell(12).getNumericCellValue()).isEqualTo(325000.0);
            assertThat(dataRow1.getCell(13).getNumericCellValue()).isEqualTo(520000.0);
            assertThat(dataRow1.getCell(14).getStringCellValue()).isEqualTo("Tồn an toàn");

            // Row 5: Zero stock item (closingStock = 0 -> MUST display "Hết hàng", NOT "ĐỦ HÀNG" from real-time stock)
            Row dataRow2 = sheet1.getRow(5);
            assertThat(dataRow2.getCell(0).getNumericCellValue()).isEqualTo(2.0);
            assertThat(dataRow2.getCell(1).getStringCellValue()).isEqualTo("Sách Bán Hết Trong Kỳ");
            assertThat(dataRow2.getCell(10).getNumericCellValue()).isEqualTo(0.0);
            assertThat(dataRow2.getCell(14).getStringCellValue()).isEqualTo("Hết hàng");

            // Total row at Row 6
            Row totalRow = sheet1.getRow(6);
            assertThat(totalRow.getCell(5).getStringCellValue()).isEqualTo("TỔNG CỘNG TOÀN KHO:");
            assertThat(totalRow.getCell(6).getNumericCellValue()).isEqualTo(15.0);
            assertThat(totalRow.getCell(10).getNumericCellValue()).isEqualTo(13.0);
            assertThat(totalRow.getCell(11).getNumericCellValue()).isEqualTo(500000.0);
            assertThat(totalRow.getCell(12).getNumericCellValue()).isEqualTo(325000.0);
            assertThat(totalRow.getCell(13).getNumericCellValue()).isEqualTo(520000.0);
        }
    }
}
