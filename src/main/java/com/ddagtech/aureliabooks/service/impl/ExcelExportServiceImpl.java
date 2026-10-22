package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.service.ExcelExportService;
import com.ddagtech.aureliabooks.util.ExcelExportUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class ExcelExportServiceImpl implements ExcelExportService {

    private static final byte[] PRIMARY_HEADER_RGB = new byte[]{30, 41, 59};
    private static final byte[] SECONDARY_HEADER_RGB = new byte[]{(byte) 217, 74, 38};
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    public void exportRevenueExcel(RevenueAnalyticsReportDto reportDto, OutputStream outputStream) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFCellStyle titleStyle = ExcelExportUtil.createTitleStyle(workbook);
            XSSFCellStyle subtitleStyle = ExcelExportUtil.createSubtitleStyle(workbook);
            XSSFCellStyle primaryHeaderStyle = ExcelExportUtil.createHeaderStyle(workbook, PRIMARY_HEADER_RGB);
            XSSFCellStyle secondaryHeaderStyle = ExcelExportUtil.createHeaderStyle(workbook, SECONDARY_HEADER_RGB);
            XSSFCellStyle currencyStyle = ExcelExportUtil.createCurrencyStyle(workbook);
            XSSFCellStyle integerStyle = ExcelExportUtil.createIntegerStyle(workbook);
            XSSFCellStyle percentStyle = ExcelExportUtil.createPercentStyle(workbook);
            XSSFCellStyle textLeftStyle = ExcelExportUtil.createTextLeftStyle(workbook);
            XSSFCellStyle textCenterStyle = ExcelExportUtil.createTextCenterStyle(workbook);
            XSSFCellStyle dateStyle = ExcelExportUtil.createDateStyle(workbook);
            XSSFCellStyle dateTimeStyle = ExcelExportUtil.createDateTimeStyle(workbook);
            XSSFCellStyle totalLabelStyle = ExcelExportUtil.createTotalRowLabelStyle(workbook);
            XSSFCellStyle totalCurrencyStyle = ExcelExportUtil.createTotalCurrencyStyle(workbook);
            XSSFCellStyle totalIntegerStyle = ExcelExportUtil.createTotalIntegerStyle(workbook);

            buildSheet1OverviewAndDaily(workbook, reportDto, titleStyle, subtitleStyle, primaryHeaderStyle,
                    currencyStyle, integerStyle, percentStyle, textLeftStyle, textCenterStyle, dateStyle,
                    totalLabelStyle, totalCurrencyStyle, totalIntegerStyle);

            buildSheet2BestSellers(workbook, reportDto, titleStyle, subtitleStyle, secondaryHeaderStyle,
                    currencyStyle, integerStyle, textLeftStyle, textCenterStyle);

            buildSheet3DeliveredOrders(workbook, reportDto, titleStyle, subtitleStyle, primaryHeaderStyle,
                    currencyStyle, integerStyle, textLeftStyle, textCenterStyle, dateTimeStyle,
                    totalLabelStyle, totalCurrencyStyle);

            workbook.write(outputStream);
            outputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException("Lỗi kết xuất file Excel: " + e.getMessage(), e);
        }
    }

    @Override
    public void exportStockValuationExcel(List<StockValuationItemDto> stockItems, OutputStream outputStream) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFCellStyle titleStyle = ExcelExportUtil.createTitleStyle(workbook);
            XSSFCellStyle subtitleStyle = ExcelExportUtil.createSubtitleStyle(workbook);
            XSSFCellStyle headerStyle = ExcelExportUtil.createHeaderStyle(workbook, PRIMARY_HEADER_RGB);
            XSSFCellStyle currencyStyle = ExcelExportUtil.createCurrencyStyle(workbook);
            XSSFCellStyle integerStyle = ExcelExportUtil.createIntegerStyle(workbook);
            XSSFCellStyle textLeftStyle = ExcelExportUtil.createTextLeftStyle(workbook);
            XSSFCellStyle textCenterStyle = ExcelExportUtil.createTextCenterStyle(workbook);
            XSSFCellStyle totalLabelStyle = ExcelExportUtil.createTotalRowLabelStyle(workbook);
            XSSFCellStyle totalCurrencyStyle = ExcelExportUtil.createTotalCurrencyStyle(workbook);
            XSSFCellStyle totalIntegerStyle = ExcelExportUtil.createTotalIntegerStyle(workbook);

            XSSFSheet sheet = workbook.createSheet("Định giá & Tồn kho");

            Row r0 = sheet.createRow(0);
            Cell c0 = r0.createCell(0);
            c0.setCellValue("BÁO CÁO TỒN KHO & ĐỊNH GIÁ TÀI SẢN KHO HÀNG - AURELIABOOK");
            c0.setCellStyle(titleStyle);

            Row r1 = sheet.createRow(1);
            Cell c1 = r1.createCell(0);
            c1.setCellValue("Thời gian kết xuất: " + LocalDateTime.now().format(DATETIME_FMT) +
                    " | Nguyên tắc kế toán: Giá vốn thực tế (Cost Basis)");
            c1.setCellStyle(subtitleStyle);

            String[] headers = {
                    "STT", "Mã SP", "Mã vạch (Barcode)", "Tên sản phẩm", "Chủng loại",
                    "Danh mục", "Số lượng tồn", "Đơn giá vốn (VND)", "Đơn giá bán (VND)",
                    "Tổng giá trị vốn (VND)", "Tổng giá trị bán (VND)", "Tình trạng tồn", "Luân chuyển kho"
            };

            Row headerRow = sheet.createRow(3);
            headerRow.setHeightInPoints(25);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 4;
            int stt = 1;
            long totalPhysicalUnits = 0;
            BigDecimal totalCostAsset = BigDecimal.ZERO;
            BigDecimal totalRetailAsset = BigDecimal.ZERO;

            if (stockItems != null) {
                for (StockValuationItemDto item : stockItems) {
                    Row row = sheet.createRow(rowIdx++);
                    row.createCell(0).setCellValue(stt++);
                    row.getCell(0).setCellStyle(textCenterStyle);

                    row.createCell(1).setCellValue(item.getProductId() != null ? item.getProductId() : 0);
                    row.getCell(1).setCellStyle(textCenterStyle);

                    row.createCell(2).setCellValue(item.getBarcode() != null ? item.getBarcode() : "");
                    row.getCell(2).setCellStyle(textCenterStyle);

                    row.createCell(3).setCellValue(item.getTitle() != null ? item.getTitle() : "");
                    row.getCell(3).setCellStyle(textLeftStyle);

                    row.createCell(4).setCellValue(item.getProductTypeLabel());
                    row.getCell(4).setCellStyle(textCenterStyle);

                    row.createCell(5).setCellValue(item.getCategoryName() != null ? item.getCategoryName() : "");
                    row.getCell(5).setCellStyle(textLeftStyle);

                    int qty = item.getStockQuantity() != null ? item.getStockQuantity() : 0;
                    totalPhysicalUnits += qty;
                    row.createCell(6).setCellValue(qty);
                    row.getCell(6).setCellStyle(integerStyle);

                    double cost = item.getOriginalCost() != null ? item.getOriginalCost().doubleValue() : 0.0;
                    row.createCell(7).setCellValue(cost);
                    row.getCell(7).setCellStyle(currencyStyle);

                    double price = item.getPrice() != null ? item.getPrice().doubleValue() : 0.0;
                    row.createCell(8).setCellValue(price);
                    row.getCell(8).setCellStyle(currencyStyle);

                    BigDecimal costVal = item.getTotalCostValue() != null ? item.getTotalCostValue() : BigDecimal.ZERO;
                    totalCostAsset = totalCostAsset.add(costVal);
                    row.createCell(9).setCellValue(costVal.doubleValue());
                    row.getCell(9).setCellStyle(currencyStyle);

                    BigDecimal retailVal = item.getTotalRetailValue() != null ? item.getTotalRetailValue() : BigDecimal.ZERO;
                    totalRetailAsset = totalRetailAsset.add(retailVal);
                    row.createCell(10).setCellValue(retailVal.doubleValue());
                    row.getCell(10).setCellStyle(currencyStyle);

                    row.createCell(11).setCellValue(item.getStockStatus() != null ? item.getStockStatus() : "");
                    row.getCell(11).setCellStyle(textCenterStyle);

                    row.createCell(12).setCellValue(item.getAgingTier() != null ? item.getAgingTier() : "");
                    row.getCell(12).setCellStyle(textCenterStyle);
                }
            }

            Row totalRow = sheet.createRow(rowIdx);
            totalRow.setHeightInPoints(22);
            for (int i = 0; i <= 5; i++) {
                Cell c = totalRow.createCell(i);
                c.setCellStyle(totalLabelStyle);
                if (i == 5) {
                    c.setCellValue("TỔNG CỘNG TÀI SẢN KHO:");
                }
            }
            Cell cQty = totalRow.createCell(6);
            cQty.setCellValue(totalPhysicalUnits);
            cQty.setCellStyle(totalIntegerStyle);

            totalRow.createCell(7).setCellStyle(totalLabelStyle);
            totalRow.createCell(8).setCellStyle(totalLabelStyle);

            Cell cCost = totalRow.createCell(9);
            cCost.setCellValue(totalCostAsset.doubleValue());
            cCost.setCellStyle(totalCurrencyStyle);

            Cell cRetail = totalRow.createCell(10);
            cRetail.setCellValue(totalRetailAsset.doubleValue());
            cRetail.setCellStyle(totalCurrencyStyle);

            totalRow.createCell(11).setCellStyle(totalLabelStyle);
            totalRow.createCell(12).setCellStyle(totalLabelStyle);

            ExcelExportUtil.autoSizeColumns(sheet, headers.length);

            workbook.write(outputStream);
            outputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException("Lỗi kết xuất file Excel tồn kho: " + e.getMessage(), e);
        }
    }

    private void buildSheet1OverviewAndDaily(XSSFWorkbook workbook, RevenueAnalyticsReportDto reportDto,
                                             XSSFCellStyle titleStyle, XSSFCellStyle subtitleStyle,
                                             XSSFCellStyle headerStyle, XSSFCellStyle currencyStyle,
                                             XSSFCellStyle integerStyle, XSSFCellStyle percentStyle,
                                             XSSFCellStyle textLeftStyle, XSSFCellStyle textCenterStyle,
                                             XSSFCellStyle dateStyle, XSSFCellStyle totalLabelStyle,
                                             XSSFCellStyle totalCurrencyStyle, XSSFCellStyle totalIntegerStyle) {

        XSSFSheet sheet = workbook.createSheet("Tổng quan & Doanh thu theo ngày");

        Row r0 = sheet.createRow(0);
        Cell c0 = r0.createCell(0);
        c0.setCellValue("BÁO CÁO DOANH THU & HIỆU QUẢ KINH DOANH - AURELIABOOK");
        c0.setCellStyle(titleStyle);

        String dateRangeDesc = "Từ ngày " + (reportDto.getFilter().getStartDate() != null ? reportDto.getFilter().getStartDate().format(DATE_FMT) : "N/A") +
                " đến ngày " + (reportDto.getFilter().getEndDate() != null ? reportDto.getFilter().getEndDate().format(DATE_FMT) : "N/A");

        Row r1 = sheet.createRow(1);
        Cell c1 = r1.createCell(0);
        c1.setCellValue("Kỳ báo cáo: " + dateRangeDesc + " | Thời gian xuất: " + LocalDateTime.now().format(DATETIME_FMT));
        c1.setCellStyle(subtitleStyle);

        Row r3 = sheet.createRow(3);
        Cell cSec1 = r3.createCell(0);
        cSec1.setCellValue("I. TỔNG QUAN CHỈ SỐ TÀI CHÍNH (ĐƠN HÀNG DELIVERED)");
        cSec1.setCellStyle(titleStyle);

        Row kpiHeader = sheet.createRow(4);
        kpiHeader.createCell(0).setCellValue("Chỉ số tài chính");
        kpiHeader.getCell(0).setCellStyle(headerStyle);
        kpiHeader.createCell(1).setCellValue("Giá trị ghi nhận");
        kpiHeader.getCell(1).setCellStyle(headerStyle);
        kpiHeader.createCell(2).setCellValue("Đơn vị tính");
        kpiHeader.getCell(2).setCellStyle(headerStyle);

        RevenueSummaryKpiDto kpi = reportDto.getKpi() != null ? reportDto.getKpi() : new RevenueSummaryKpiDto();
        PaymentBreakdownDto payment = reportDto.getPaymentBreakdown() != null ? reportDto.getPaymentBreakdown() : new PaymentBreakdownDto();

        Object[][] kpiData = {
                {"Tổng doanh thu thực tế (DELIVERED)", kpi.getGrossRevenue().doubleValue(), currencyStyle, "Đồng"},
                {"Số đơn hàng hoàn tất", (double) kpi.getTotalOrders(), integerStyle, "Đơn hàng"},
                {"Giá trị trung bình mỗi đơn (AOV)", kpi.getAverageOrderValue().doubleValue(), currencyStyle, "Đồng/Đơn"},
                {"Tổng cước vận chuyển thu được", kpi.getTotalShippingFee().doubleValue(), currencyStyle, "Đồng"},
                {"Doanh thu qua COD (Tiền mặt)", payment.getCodRevenue().doubleValue(), currencyStyle, "Đồng"},
                {"Doanh thu qua VNPAY (Điện tử)", payment.getVnpayRevenue().doubleValue(), currencyStyle, "Đồng"},
                {"Tỷ lệ thanh toán không dùng tiền mặt (BO-4)", payment.getElectronicSharePercent() / 100.0, percentStyle, "%"}
        };

        int kpiRowIdx = 5;
        for (Object[] rowDef : kpiData) {
            Row row = sheet.createRow(kpiRowIdx++);
            row.createCell(0).setCellValue((String) rowDef[0]);
            row.getCell(0).setCellStyle(textLeftStyle);

            Cell valCell = row.createCell(1);
            valCell.setCellValue((Double) rowDef[1]);
            valCell.setCellStyle((XSSFCellStyle) rowDef[2]);

            row.createCell(2).setCellValue((String) rowDef[3]);
            row.getCell(2).setCellStyle(textCenterStyle);
        }

        int dailyStartRow = kpiRowIdx + 2;
        Row rSec2 = sheet.createRow(dailyStartRow);
        Cell cSec2 = rSec2.createCell(0);
        cSec2.setCellValue("II. BIẾN ĐỘNG DOANH THU CHI TIẾT THEO NGÀY");
        cSec2.setCellStyle(titleStyle);

        Row dailyHeader = sheet.createRow(dailyStartRow + 1);
        dailyHeader.setHeightInPoints(24);
        String[] dailyCols = {"STT", "Ngày giao dịch", "Số đơn hoàn tất", "Doanh thu trong ngày (VND)"};
        for (int i = 0; i < dailyCols.length; i++) {
            Cell cell = dailyHeader.createCell(i);
            cell.setCellValue(dailyCols[i]);
            cell.setCellStyle(headerStyle);
        }

        int dRowIdx = dailyStartRow + 2;
        int stt = 1;
        long totalOrdersSum = 0;
        BigDecimal totalRevenueSum = BigDecimal.ZERO;

        if (reportDto.getDailyTrends() != null) {
            for (DailyRevenueDto day : reportDto.getDailyTrends()) {
                Row row = sheet.createRow(dRowIdx++);
                row.createCell(0).setCellValue(stt++);
                row.getCell(0).setCellStyle(textCenterStyle);

                Cell cDate = row.createCell(1);
                cDate.setCellValue(day.getDate() != null ? java.sql.Date.valueOf(day.getDate()) : null);
                cDate.setCellStyle(dateStyle);

                totalOrdersSum += day.getOrderCount();
                Cell cOrders = row.createCell(2);
                cOrders.setCellValue(day.getOrderCount());
                cOrders.setCellStyle(integerStyle);

                BigDecimal rev = day.getRevenue() != null ? day.getRevenue() : BigDecimal.ZERO;
                totalRevenueSum = totalRevenueSum.add(rev);
                Cell cRev = row.createCell(3);
                cRev.setCellValue(rev.doubleValue());
                cRev.setCellStyle(currencyStyle);
            }
        }

        Row totalRow = sheet.createRow(dRowIdx);
        totalRow.setHeightInPoints(22);
        Cell cTotLabel1 = totalRow.createCell(0);
        cTotLabel1.setCellStyle(totalLabelStyle);
        Cell cTotLabel2 = totalRow.createCell(1);
        cTotLabel2.setCellValue("TỔNG CỘNG:");
        cTotLabel2.setCellStyle(totalLabelStyle);

        Cell cTotOrders = totalRow.createCell(2);
        cTotOrders.setCellValue(totalOrdersSum);
        cTotOrders.setCellStyle(totalIntegerStyle);

        Cell cTotRev = totalRow.createCell(3);
        cTotRev.setCellValue(totalRevenueSum.doubleValue());
        cTotRev.setCellStyle(totalCurrencyStyle);

        ExcelExportUtil.autoSizeColumns(sheet, dailyCols.length);
    }

    private void buildSheet2BestSellers(XSSFWorkbook workbook, RevenueAnalyticsReportDto reportDto,
                                        XSSFCellStyle titleStyle, XSSFCellStyle subtitleStyle,
                                        XSSFCellStyle headerStyle, XSSFCellStyle currencyStyle,
                                        XSSFCellStyle integerStyle, XSSFCellStyle textLeftStyle,
                                        XSSFCellStyle textCenterStyle) {

        XSSFSheet sheet = workbook.createSheet("Top Sản phẩm Bán chạy");

        Row r0 = sheet.createRow(0);
        Cell c0 = r0.createCell(0);
        c0.setCellValue("BẢNG XẾP HẠNG TOP 5 SẢN PHẨM BÁN CHẠY NHẤT (BESTSELLERS)");
        c0.setCellStyle(titleStyle);

        Row r1 = sheet.createRow(1);
        Cell c1 = r1.createCell(0);
        c1.setCellValue("Số liệu đánh giá theo tổng số lượng bán ra (Quantity Sold) từ các đơn hàng DELIVERED");
        c1.setCellStyle(subtitleStyle);

        Row r3 = sheet.createRow(3);
        Cell cB1 = r3.createCell(0);
        cB1.setCellValue("1. TOP 5 SÁCH BÁN CHẠY NHẤT");
        cB1.setCellStyle(titleStyle);

        String[] headers = {"Xếp hạng", "Mã vạch (Barcode)", "Tên sản phẩm", "Chủng loại", "Số lượng bán", "Tổng doanh thu (VND)"};
        Row bHeader = sheet.createRow(4);
        bHeader.setHeightInPoints(24);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = bHeader.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int bRowIdx = 5;
        int rank = 1;
        if (reportDto.getTopBooks() != null && !reportDto.getTopBooks().isEmpty()) {
            for (TopProductSaleDto book : reportDto.getTopBooks()) {
                Row row = sheet.createRow(bRowIdx++);
                row.createCell(0).setCellValue("Top " + rank++);
                row.getCell(0).setCellStyle(textCenterStyle);

                row.createCell(1).setCellValue(book.getBarcode() != null ? book.getBarcode() : "");
                row.getCell(1).setCellStyle(textCenterStyle);

                row.createCell(2).setCellValue(book.getTitle() != null ? book.getTitle() : "");
                row.getCell(2).setCellStyle(textLeftStyle);

                row.createCell(3).setCellValue(book.getProductTypeLabel());
                row.getCell(3).setCellStyle(textCenterStyle);

                row.createCell(4).setCellValue(book.getTotalQuantitySold());
                row.getCell(4).setCellStyle(integerStyle);

                double rev = book.getTotalRevenue() != null ? book.getTotalRevenue().doubleValue() : 0.0;
                row.createCell(5).setCellValue(rev);
                row.getCell(5).setCellStyle(currencyStyle);
            }
        } else {
            Row emptyRow = sheet.createRow(bRowIdx++);
            emptyRow.createCell(0).setCellValue("Chưa có dữ liệu sách bán chạy trong kỳ.");
        }

        int sStartRow = bRowIdx + 2;
        Row rS = sheet.createRow(sStartRow);
        Cell cS1 = rS.createCell(0);
        cS1.setCellValue("2. TOP 5 VĂN PHÒNG PHẨM BÁN CHẠY NHẤT");
        cS1.setCellStyle(titleStyle);

        Row sHeader = sheet.createRow(sStartRow + 1);
        sHeader.setHeightInPoints(24);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = sHeader.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int sRowIdx = sStartRow + 2;
        rank = 1;
        if (reportDto.getTopStationeries() != null && !reportDto.getTopStationeries().isEmpty()) {
            for (TopProductSaleDto stat : reportDto.getTopStationeries()) {
                Row row = sheet.createRow(sRowIdx++);
                row.createCell(0).setCellValue("Top " + rank++);
                row.getCell(0).setCellStyle(textCenterStyle);

                row.createCell(1).setCellValue(stat.getBarcode() != null ? stat.getBarcode() : "");
                row.getCell(1).setCellStyle(textCenterStyle);

                row.createCell(2).setCellValue(stat.getTitle() != null ? stat.getTitle() : "");
                row.getCell(2).setCellStyle(textLeftStyle);

                row.createCell(3).setCellValue(stat.getProductTypeLabel());
                row.getCell(3).setCellStyle(textCenterStyle);

                row.createCell(4).setCellValue(stat.getTotalQuantitySold());
                row.getCell(4).setCellStyle(integerStyle);

                double rev = stat.getTotalRevenue() != null ? stat.getTotalRevenue().doubleValue() : 0.0;
                row.createCell(5).setCellValue(rev);
                row.getCell(5).setCellStyle(currencyStyle);
            }
        } else {
            Row emptyRow = sheet.createRow(sRowIdx++);
            emptyRow.createCell(0).setCellValue("Chưa có dữ liệu văn phòng phẩm bán chạy trong kỳ.");
        }

        ExcelExportUtil.autoSizeColumns(sheet, headers.length);
    }

    private void buildSheet3DeliveredOrders(XSSFWorkbook workbook, RevenueAnalyticsReportDto reportDto,
                                            XSSFCellStyle titleStyle, XSSFCellStyle subtitleStyle,
                                            XSSFCellStyle headerStyle, XSSFCellStyle currencyStyle,
                                            XSSFCellStyle integerStyle, XSSFCellStyle textLeftStyle,
                                            XSSFCellStyle textCenterStyle, XSSFCellStyle dateTimeStyle,
                                            XSSFCellStyle totalLabelStyle, XSSFCellStyle totalCurrencyStyle) {

        XSSFSheet sheet = workbook.createSheet("Danh sách Đơn hàng Hoàn tất");

        Row r0 = sheet.createRow(0);
        Cell c0 = r0.createCell(0);
        c0.setCellValue("DANH SÁCH ĐƠN HÀNG HOÀN TẤT TRONG KỲ (DELIVERED)");
        c0.setCellStyle(titleStyle);

        Row r1 = sheet.createRow(1);
        Cell c1 = r1.createCell(0);
        c1.setCellValue("Chỉ ghi nhận các đơn giao hàng thành công và thanh toán PAID cấu thành doanh thu");
        c1.setCellStyle(subtitleStyle);

        String[] headers = {
                "STT", "Mã đơn hàng", "Người nhận hàng", "Số điện thoại",
                "Kênh thanh toán", "Ngày đặt hàng", "Ngày giao hàng",
                "Cước vận chuyển (VND)", "Tổng tiền thanh toán (VND)"
        };

        Row headerRow = sheet.createRow(3);
        headerRow.setHeightInPoints(25);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 4;
        int stt = 1;
        BigDecimal totalShippingSum = BigDecimal.ZERO;
        BigDecimal totalAmountSum = BigDecimal.ZERO;

        if (reportDto.getRecentDeliveredOrders() != null && !reportDto.getRecentDeliveredOrders().isEmpty()) {
            for (RevenueOrderSummaryDto order : reportDto.getRecentDeliveredOrders()) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(stt++);
                row.getCell(0).setCellStyle(textCenterStyle);

                row.createCell(1).setCellValue(order.getOrderCode() != null ? order.getOrderCode() : "");
                row.getCell(1).setCellStyle(textCenterStyle);

                row.createCell(2).setCellValue(order.getRecipientName() != null ? order.getRecipientName() : "");
                row.getCell(2).setCellStyle(textLeftStyle);

                row.createCell(3).setCellValue(order.getPhone() != null ? order.getPhone() : "");
                row.getCell(3).setCellStyle(textCenterStyle);

                row.createCell(4).setCellValue(order.getPaymentMethodLabel());
                row.getCell(4).setCellStyle(textCenterStyle);

                Cell cCreated = row.createCell(5);
                cCreated.setCellValue(order.getCreatedAt() != null ? java.sql.Timestamp.valueOf(order.getCreatedAt()) : null);
                cCreated.setCellStyle(dateTimeStyle);

                Cell cDelivered = row.createCell(6);
                cDelivered.setCellValue(order.getDeliveredAt() != null ? java.sql.Timestamp.valueOf(order.getDeliveredAt()) : null);
                cDelivered.setCellStyle(dateTimeStyle);

                BigDecimal ship = order.getShippingFee() != null ? order.getShippingFee() : BigDecimal.ZERO;
                totalShippingSum = totalShippingSum.add(ship);
                Cell cShip = row.createCell(7);
                cShip.setCellValue(ship.doubleValue());
                cShip.setCellStyle(currencyStyle);

                BigDecimal total = order.getFinalTotalAmount() != null ? order.getFinalTotalAmount() : BigDecimal.ZERO;
                totalAmountSum = totalAmountSum.add(total);
                Cell cTotal = row.createCell(8);
                cTotal.setCellValue(total.doubleValue());
                cTotal.setCellStyle(currencyStyle);
            }

            Row totalRow = sheet.createRow(rowIdx);
            totalRow.setHeightInPoints(22);
            for (int i = 0; i <= 6; i++) {
                Cell c = totalRow.createCell(i);
                c.setCellStyle(totalLabelStyle);
                if (i == 6) {
                    c.setCellValue("TỔNG CỘNG:");
                }
            }

            Cell cTotShip = totalRow.createCell(7);
            cTotShip.setCellValue(totalShippingSum.doubleValue());
            cTotShip.setCellStyle(totalCurrencyStyle);

            Cell cTotAmount = totalRow.createCell(8);
            cTotAmount.setCellValue(totalAmountSum.doubleValue());
            cTotAmount.setCellStyle(totalCurrencyStyle);
        } else {
            Row emptyRow = sheet.createRow(rowIdx);
            emptyRow.createCell(0).setCellValue("Không có đơn hàng hoàn tất nào trong khoảng thời gian này.");
        }

        ExcelExportUtil.autoSizeColumns(sheet, headers.length);
    }
}
