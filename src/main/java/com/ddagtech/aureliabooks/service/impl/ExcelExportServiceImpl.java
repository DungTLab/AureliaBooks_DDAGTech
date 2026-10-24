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
            buildValuationWorkbook(workbook, stockItems);
            workbook.write(outputStream);
            outputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException("Lỗi kết xuất file Excel tồn kho: " + e.getMessage(), e);
        }
    }

    @Override
    public void exportPeriodStockReport(StockMovementFilterRequest filter,
                                         List<StockMovementReportItemDto> items,
                                         List<StockValuationItemDto> valuationItems,
                                         OutputStream outputStream) {
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

            // Sheet 1: Biến động Tồn kho theo Kỳ (UC27 Primary Document Aggregate)
            buildStockMovementSheet(workbook, filter, items, titleStyle, subtitleStyle, headerStyle,
                    currencyStyle, integerStyle, textLeftStyle, textCenterStyle,
                    totalLabelStyle, totalCurrencyStyle, totalIntegerStyle);

            // Sheet 2: Định giá Tồn kho Hiện tại (Supplementary Snapshot Valuation)
            if (valuationItems != null) {
                buildValuationSheet(workbook, valuationItems, titleStyle, subtitleStyle, headerStyle,
                        currencyStyle, integerStyle, textLeftStyle, textCenterStyle,
                        totalLabelStyle, totalCurrencyStyle, totalIntegerStyle);
            }

            workbook.write(outputStream);
            outputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException("Lỗi kết xuất file Excel báo cáo biến động kho: " + e.getMessage(), e);
        }
    }

    private void buildStockMovementSheet(XSSFWorkbook workbook, StockMovementFilterRequest filter,
                                         List<StockMovementReportItemDto> items,
                                         XSSFCellStyle titleStyle, XSSFCellStyle subtitleStyle,
                                         XSSFCellStyle headerStyle, XSSFCellStyle currencyStyle,
                                         XSSFCellStyle integerStyle, XSSFCellStyle textLeftStyle,
                                         XSSFCellStyle textCenterStyle, XSSFCellStyle totalLabelStyle,
                                         XSSFCellStyle totalCurrencyStyle, XSSFCellStyle totalIntegerStyle) {

        XSSFSheet sheet = workbook.createSheet("Biến động Tồn kho theo Kỳ");

        Row r0 = sheet.createRow(0);
        Cell c0 = r0.createCell(0);
        c0.setCellValue("BÁO CÁO BIẾN ĐỘNG TỒN KHO THEO KỲ CHỨNG TỪ - AURELIABOOK");
        c0.setCellStyle(titleStyle);

        String startStr = (filter != null && filter.getStartDate() != null) ? filter.getStartDate().format(DATE_FMT) : "Đầu kỳ";
        String endStr = (filter != null && filter.getEndDate() != null) ? filter.getEndDate().format(DATE_FMT) : "Hiện tại";

        Row r1 = sheet.createRow(1);
        Cell c1 = r1.createCell(0);
        c1.setCellValue("Kỳ báo cáo: Từ " + startStr + " đến " + endStr +
                " | Căn cứ chứng từ nhập kho, xuất bán, hủy đơn và hoàn trả đã posting");
        c1.setCellStyle(subtitleStyle);

        String[] headers = {
                "STT", "Mã SP", "Mã vạch (Barcode)", "Tên sản phẩm", "Chủng loại",
                "Danh mục", "Trạng thái KD", "Đơn giá vốn (VND)", "Đơn giá bán (VND)",
                "Tồn đầu kỳ", "Nhập trong kỳ", "Xuất bán (Trừ kho)", "Hủy đơn hoàn kho",
                "Trả hàng hoàn kho", "Tồn cuối kỳ", "Giá trị tồn đầu (VND)", "Giá trị tồn cuối (VND)"
        };

        Row headerRow = sheet.createRow(3);
        headerRow.setHeightInPoints(26);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 4;
        int stt = 1;
        long sumOpening = 0;
        long sumImport = 0;
        long sumDeduct = 0;
        long sumCancelRestore = 0;
        long sumReturnRestore = 0;
        long sumClosing = 0;
        BigDecimal sumOpeningVal = BigDecimal.ZERO;
        BigDecimal sumClosingVal = BigDecimal.ZERO;

        if (items != null) {
            for (StockMovementReportItemDto item : items) {
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

                row.createCell(6).setCellValue(item.getActiveStatusLabel());
                row.getCell(6).setCellStyle(textCenterStyle);

                double cost = item.getOriginalCost() != null ? item.getOriginalCost().doubleValue() : 0.0;
                row.createCell(7).setCellValue(cost);
                row.getCell(7).setCellStyle(currencyStyle);

                double price = item.getPrice() != null ? item.getPrice().doubleValue() : 0.0;
                row.createCell(8).setCellValue(price);
                row.getCell(8).setCellStyle(currencyStyle);

                sumOpening += item.getOpeningStock();
                row.createCell(9).setCellValue(item.getOpeningStock());
                row.getCell(9).setCellStyle(integerStyle);

                sumImport += item.getImportQty();
                row.createCell(10).setCellValue(item.getImportQty());
                row.getCell(10).setCellStyle(integerStyle);

                sumDeduct += item.getOrderDeductQty();
                row.createCell(11).setCellValue(item.getOrderDeductQty());
                row.getCell(11).setCellStyle(integerStyle);

                sumCancelRestore += item.getCancelRestoreQty();
                row.createCell(12).setCellValue(item.getCancelRestoreQty());
                row.getCell(12).setCellStyle(integerStyle);

                sumReturnRestore += item.getReturnRestoreQty();
                row.createCell(13).setCellValue(item.getReturnRestoreQty());
                row.getCell(13).setCellStyle(integerStyle);

                sumClosing += item.getClosingStock();
                row.createCell(14).setCellValue(item.getClosingStock());
                row.getCell(14).setCellStyle(integerStyle);

                BigDecimal openVal = item.getOpeningCostValue() != null ? item.getOpeningCostValue() : BigDecimal.ZERO;
                sumOpeningVal = sumOpeningVal.add(openVal);
                row.createCell(15).setCellValue(openVal.doubleValue());
                row.getCell(15).setCellStyle(currencyStyle);

                BigDecimal closeVal = item.getClosingCostValue() != null ? item.getClosingCostValue() : BigDecimal.ZERO;
                sumClosingVal = sumClosingVal.add(closeVal);
                row.createCell(16).setCellValue(closeVal.doubleValue());
                row.getCell(16).setCellStyle(currencyStyle);
            }
        }

        Row totalRow = sheet.createRow(rowIdx);
        totalRow.setHeightInPoints(24);
        for (int i = 0; i <= 8; i++) {
            Cell c = totalRow.createCell(i);
            c.setCellStyle(totalLabelStyle);
            if (i == 8) {
                c.setCellValue("TỔNG CỘNG:");
            }
        }

        Cell cOpen = totalRow.createCell(9);
        cOpen.setCellValue(sumOpening);
        cOpen.setCellStyle(totalIntegerStyle);

        Cell cImp = totalRow.createCell(10);
        cImp.setCellValue(sumImport);
        cImp.setCellStyle(totalIntegerStyle);

        Cell cDed = totalRow.createCell(11);
        cDed.setCellValue(sumDeduct);
        cDed.setCellStyle(totalIntegerStyle);

        Cell cCanc = totalRow.createCell(12);
        cCanc.setCellValue(sumCancelRestore);
        cCanc.setCellStyle(totalIntegerStyle);

        Cell cRet = totalRow.createCell(13);
        cRet.setCellValue(sumReturnRestore);
        cRet.setCellStyle(totalIntegerStyle);

        Cell cClose = totalRow.createCell(14);
        cClose.setCellValue(sumClosing);
        cClose.setCellStyle(totalIntegerStyle);

        Cell cOpenV = totalRow.createCell(15);
        cOpenV.setCellValue(sumOpeningVal.doubleValue());
        cOpenV.setCellStyle(totalCurrencyStyle);

        Cell cCloseV = totalRow.createCell(16);
        cCloseV.setCellValue(sumClosingVal.doubleValue());
        cCloseV.setCellStyle(totalCurrencyStyle);

        ExcelExportUtil.autoSizeColumns(sheet, headers.length);
    }

    private void buildValuationWorkbook(XSSFWorkbook workbook, List<StockValuationItemDto> stockItems) {
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

        buildValuationSheet(workbook, stockItems, titleStyle, subtitleStyle, headerStyle,
                currencyStyle, integerStyle, textLeftStyle, textCenterStyle,
                totalLabelStyle, totalCurrencyStyle, totalIntegerStyle);
    }

    private void buildValuationSheet(XSSFWorkbook workbook, List<StockValuationItemDto> stockItems,
                                     XSSFCellStyle titleStyle, XSSFCellStyle subtitleStyle,
                                     XSSFCellStyle headerStyle, XSSFCellStyle currencyStyle,
                                     XSSFCellStyle integerStyle, XSSFCellStyle textLeftStyle,
                                     XSSFCellStyle textCenterStyle, XSSFCellStyle totalLabelStyle,
                                     XSSFCellStyle totalCurrencyStyle, XSSFCellStyle totalIntegerStyle) {

        XSSFSheet sheet = workbook.createSheet("Định giá & Tồn kho Hiện tại");

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
                "Danh mục", "Trạng thái KD", "Số lượng tồn", "Đơn giá vốn (VND)", "Đơn giá bán (VND)",
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

                row.createCell(6).setCellValue(item.getActiveStatusLabel());
                row.getCell(6).setCellStyle(textCenterStyle);

                int qty = item.getStockQuantity() != null ? item.getStockQuantity() : 0;
                totalPhysicalUnits += qty;
                row.createCell(7).setCellValue(qty);
                row.getCell(7).setCellStyle(integerStyle);

                double cost = item.getOriginalCost() != null ? item.getOriginalCost().doubleValue() : 0.0;
                row.createCell(8).setCellValue(cost);
                row.getCell(8).setCellStyle(currencyStyle);

                double price = item.getPrice() != null ? item.getPrice().doubleValue() : 0.0;
                row.createCell(9).setCellValue(price);
                row.getCell(9).setCellStyle(currencyStyle);

                BigDecimal costVal = item.getTotalCostValue() != null ? item.getTotalCostValue() : BigDecimal.ZERO;
                totalCostAsset = totalCostAsset.add(costVal);
                row.createCell(10).setCellValue(costVal.doubleValue());
                row.getCell(10).setCellStyle(currencyStyle);

                BigDecimal retailVal = item.getTotalRetailValue() != null ? item.getTotalRetailValue() : BigDecimal.ZERO;
                totalRetailAsset = totalRetailAsset.add(retailVal);
                row.createCell(11).setCellValue(retailVal.doubleValue());
                row.getCell(11).setCellStyle(currencyStyle);

                row.createCell(12).setCellValue(item.getStockStatus() != null ? item.getStockStatus() : "");
                row.getCell(12).setCellStyle(textCenterStyle);

                row.createCell(13).setCellValue(item.getAgingTier() != null ? item.getAgingTier() : "");
                row.getCell(13).setCellStyle(textCenterStyle);
            }
        }

        Row totalRow = sheet.createRow(rowIdx);
        totalRow.setHeightInPoints(22);
        for (int i = 0; i <= 6; i++) {
            Cell c = totalRow.createCell(i);
            c.setCellStyle(totalLabelStyle);
            if (i == 6) {
                c.setCellValue("TỔNG CỘNG TÀI SẢN KHO:");
            }
        }
        Cell cQty = totalRow.createCell(7);
        cQty.setCellValue(totalPhysicalUnits);
        cQty.setCellStyle(totalIntegerStyle);

        totalRow.createCell(8).setCellStyle(totalLabelStyle);
        totalRow.createCell(9).setCellStyle(totalLabelStyle);

        Cell cCost = totalRow.createCell(10);
        cCost.setCellValue(totalCostAsset.doubleValue());
        cCost.setCellStyle(totalCurrencyStyle);

        Cell cRetail = totalRow.createCell(11);
        cRetail.setCellValue(totalRetailAsset.doubleValue());
        cRetail.setCellStyle(totalCurrencyStyle);

        totalRow.createCell(12).setCellStyle(totalLabelStyle);
        totalRow.createCell(13).setCellStyle(totalLabelStyle);

        ExcelExportUtil.autoSizeColumns(sheet, headers.length);
    }

    private void buildSheet1OverviewAndDaily(XSSFWorkbook workbook, RevenueAnalyticsReportDto reportDto,
                                             XSSFCellStyle titleStyle, XSSFCellStyle subtitleStyle,
                                             XSSFCellStyle headerStyle, XSSFCellStyle currencyStyle,
                                             XSSFCellStyle integerStyle, XSSFCellStyle percentStyle,
                                             XSSFCellStyle textLeftStyle, XSSFCellStyle textCenterStyle,
                                             XSSFCellStyle dateStyle, XSSFCellStyle totalLabelStyle,
                                             XSSFCellStyle totalCurrencyStyle, XSSFCellStyle totalIntegerStyle) {

        String sheetName = reportDto.isMonthlyGranularity() ? "Tổng quan & Doanh thu tháng" : "Tổng quan & Doanh thu theo ngày";
        XSSFSheet sheet = workbook.createSheet(sheetName);

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
                {"Tổng doanh thu thực tế (DELIVERED & PAID)", kpi.getGrossRevenue().doubleValue(), currencyStyle, "Đồng"},
                {"Số đơn hàng hoàn tất & đã thanh toán", (double) kpi.getTotalOrders(), integerStyle, "Đơn hàng"},
                {"Giá trị trung bình mỗi đơn (AOV)", kpi.getAverageOrderValue().doubleValue(), currencyStyle, "Đồng/Đơn"},
                {"Tổng cước vận chuyển thu được", kpi.getTotalShippingFee().doubleValue(), currencyStyle, "Đồng"},
                {"Doanh thu qua COD (Tiền mặt)", payment.getCodRevenue().doubleValue(), currencyStyle, "Đồng"},
                {"Doanh thu qua VNPAY (Điện tử)", payment.getVnpayRevenue().doubleValue(), currencyStyle, "Đồng"},
                {"Tỷ lệ đơn thanh toán không dùng tiền mặt (BO-4)", payment.getElectronicSharePercent() / 100.0, percentStyle, "%"}
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
        Row rDailySec = sheet.createRow(dailyStartRow);
        Cell cDailySec = rDailySec.createCell(0);
        cDailySec.setCellValue(reportDto.isMonthlyGranularity() ? "II. BẢNG XU HƯỚNG DOANH THU THEO THÁNG" : "II. BẢNG CHI TIẾT DOANH THU THEO NGÀY");
        cDailySec.setCellStyle(titleStyle);

        Row dailyHeader = sheet.createRow(dailyStartRow + 1);
        dailyHeader.setHeightInPoints(24);
        String periodHeader = reportDto.isMonthlyGranularity() ? "Kỳ giao dịch (Tháng/Năm)" : "Ngày giao dịch";
        String revHeader = reportDto.isMonthlyGranularity() ? "Doanh thu trong tháng (VND)" : "Doanh thu trong ngày (VND)";
        String[] dailyCols = {"STT", periodHeader, "Số đơn hoàn tất", revHeader};
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
                if (reportDto.isMonthlyGranularity()) {
                    cDate.setCellValue(day.getDisplayLabel());
                    cDate.setCellStyle(textCenterStyle);
                } else {
                    cDate.setCellValue(day.getDate() != null ? java.sql.Date.valueOf(day.getDate()) : null);
                    cDate.setCellStyle(dateStyle);
                }

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
        c1.setCellValue("Số liệu đánh giá theo tổng số lượng bán ra (Quantity Sold) từ các đơn hàng DELIVERED & PAID");
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
        if (reportDto.getTopBooks() != null) {
            for (TopProductSaleDto item : reportDto.getTopBooks()) {
                Row row = sheet.createRow(bRowIdx++);
                row.createCell(0).setCellValue(rank++);
                row.getCell(0).setCellStyle(textCenterStyle);

                row.createCell(1).setCellValue(item.getBarcode() != null ? item.getBarcode() : "");
                row.getCell(1).setCellStyle(textCenterStyle);

                row.createCell(2).setCellValue(item.getTitle() != null ? item.getTitle() : "");
                row.getCell(2).setCellStyle(textLeftStyle);

                row.createCell(3).setCellValue("Sách");
                row.getCell(3).setCellStyle(textCenterStyle);

                row.createCell(4).setCellValue(item.getTotalQuantitySold());
                row.getCell(4).setCellStyle(integerStyle);

                double rev = item.getTotalRevenue() != null ? item.getTotalRevenue().doubleValue() : 0.0;
                row.createCell(5).setCellValue(rev);
                row.getCell(5).setCellStyle(currencyStyle);
            }
        }

        int stStartRow = bRowIdx + 2;
        Row rStTitle = sheet.createRow(stStartRow);
        Cell cStTitle = rStTitle.createCell(0);
        cStTitle.setCellValue("2. TOP 5 VĂN PHÒNG PHẨM BÁN CHẠY NHẤT");
        cStTitle.setCellStyle(titleStyle);

        Row stHeader = sheet.createRow(stStartRow + 1);
        stHeader.setHeightInPoints(24);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = stHeader.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int stRowIdx = stStartRow + 2;
        rank = 1;
        if (reportDto.getTopStationeries() != null) {
            for (TopProductSaleDto item : reportDto.getTopStationeries()) {
                Row row = sheet.createRow(stRowIdx++);
                row.createCell(0).setCellValue(rank++);
                row.getCell(0).setCellStyle(textCenterStyle);

                row.createCell(1).setCellValue(item.getBarcode() != null ? item.getBarcode() : "");
                row.getCell(1).setCellStyle(textCenterStyle);

                row.createCell(2).setCellValue(item.getTitle() != null ? item.getTitle() : "");
                row.getCell(2).setCellStyle(textLeftStyle);

                row.createCell(3).setCellValue("Văn phòng phẩm");
                row.getCell(3).setCellStyle(textCenterStyle);

                row.createCell(4).setCellValue(item.getTotalQuantitySold());
                row.getCell(4).setCellStyle(integerStyle);

                double rev = item.getTotalRevenue() != null ? item.getTotalRevenue().doubleValue() : 0.0;
                row.createCell(5).setCellValue(rev);
                row.getCell(5).setCellStyle(currencyStyle);
            }
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
        c0.setCellValue("DANH SÁCH CHI TIẾT ĐƠN HÀNG DELIVERED & PAID TRONG KỲ - AURELIABOOK");
        c0.setCellStyle(titleStyle);

        Row r1 = sheet.createRow(1);
        Cell c1 = r1.createCell(0);
        c1.setCellValue("Chỉ bao gồm các đơn hàng đã thanh toán và giao thành công. Sử dụng đối soát doanh thu chi tiết.");
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

        // F03: Use complete export list (all orders in period without 100 limit)
        List<RevenueOrderSummaryDto> exportOrders = reportDto.getOrdersForExport();

        if (exportOrders != null && !exportOrders.isEmpty()) {
            for (RevenueOrderSummaryDto order : exportOrders) {
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
