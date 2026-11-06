package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.service.ExcelExportService;
import com.ddagtech.aureliabooks.util.ExcelExportUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class ExcelExportServiceImpl implements ExcelExportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale VI_VN = Locale.of("vi", "VN");

    private static String formatNumber(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return NumberFormat.getInstance(VI_VN).format(value);
    }

    private static String formatNumber(long value) {
        return NumberFormat.getInstance(VI_VN).format(value);
    }

    @Override
    public void exportRevenueExcel(RevenueAnalyticsReportDto reportDto, OutputStream outputStream) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            buildSheet1OverviewAndDaily(workbook, reportDto);
            buildSheet2DeliveredOrders(workbook, reportDto);

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
            buildUnifiedStockMovementSheet(workbook, filter, items, valuationItems);
            workbook.write(outputStream);
            outputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException("Lỗi kết xuất file Excel báo cáo biến động kho: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // 1. REVENUE REPORT: SHEET 1 (OVERVIEW & DAILY TRENDS)
    // =========================================================================
    private void buildSheet1OverviewAndDaily(XSSFWorkbook workbook, RevenueAnalyticsReportDto reportDto) {
        String sheetTitle = (reportDto != null && reportDto.isMonthlyGranularity()) ? "Tổng quan & Doanh thu tháng" : "Tổng quan & Doanh thu theo ngày";
        XSSFSheet sheet = workbook.createSheet(sheetTitle);
        sheet.createFreezePane(0, 14);
        sheet.setDisplayGridlines(true);

        XSSFCellStyle titleStyle = ExcelExportUtil.createTitleStyle(workbook);
        XSSFCellStyle subtitleStyle = ExcelExportUtil.createSubtitleStyle(workbook);
        XSSFCellStyle sectionStyle = ExcelExportUtil.createSectionStyle(workbook);

        XSSFCellStyle headerLeft = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.LEFT);
        XSSFCellStyle headerRight = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.RIGHT);
        XSSFCellStyle headerCenter = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.CENTER);

        XSSFCellStyle textLeftWhite = ExcelExportUtil.createTextLeftStyle(workbook, false, false);
        XSSFCellStyle textLeftZebra = ExcelExportUtil.createTextLeftStyle(workbook, true, false);
        XSSFCellStyle textLeftWhiteBold = ExcelExportUtil.createTextLeftStyle(workbook, false, true);
        XSSFCellStyle textLeftZebraBold = ExcelExportUtil.createTextLeftStyle(workbook, true, true);

        XSSFCellStyle textRightWhite = ExcelExportUtil.createCurrencyStyle(workbook, false, false);
        XSSFCellStyle textRightZebra = ExcelExportUtil.createCurrencyStyle(workbook, true, false);
        XSSFCellStyle textRightWhiteBold = ExcelExportUtil.createCurrencyStyle(workbook, false, true);
        XSSFCellStyle textRightZebraBold = ExcelExportUtil.createCurrencyStyle(workbook, true, true);

        XSSFCellStyle textCenterWhite = ExcelExportUtil.createTextCenterStyle(workbook, false, false);
        XSSFCellStyle textCenterZebra = ExcelExportUtil.createTextCenterStyle(workbook, true, false);

        XSSFCellStyle integerWhite = ExcelExportUtil.createIntegerStyle(workbook, false, false);
        XSSFCellStyle integerZebra = ExcelExportUtil.createIntegerStyle(workbook, true, false);

        XSSFCellStyle currencyWhite = ExcelExportUtil.createCurrencyStyle(workbook, false, false);
        XSSFCellStyle currencyZebra = ExcelExportUtil.createCurrencyStyle(workbook, true, false);

        XSSFCellStyle dateWhite = ExcelExportUtil.createDateStyle(workbook, false);
        XSSFCellStyle dateZebra = ExcelExportUtil.createDateStyle(workbook, true);

        XSSFCellStyle totalLabelStyle = ExcelExportUtil.createTotalRowLabelStyle(workbook);
        XSSFCellStyle totalCurrencyStyle = ExcelExportUtil.createTotalCurrencyStyle(workbook);
        XSSFCellStyle totalIntegerStyle = ExcelExportUtil.createTotalIntegerStyle(workbook);

        // Row 0: Banner Title
        Row r0 = sheet.createRow(0);
        r0.setHeightInPoints(26);
        Cell c0 = r0.createCell(0);
        c0.setCellValue("BÁO CÁO DOANH THU THEO KỲ");
        c0.setCellStyle(titleStyle);

        // Row 1: Subtitle
        String startStr = (reportDto != null && reportDto.getFilter() != null && reportDto.getFilter().getStartDate() != null)
                ? reportDto.getFilter().getStartDate().format(DATE_FMT) : "Đầu kỳ";
        String endStr = (reportDto != null && reportDto.getFilter() != null && reportDto.getFilter().getEndDate() != null)
                ? reportDto.getFilter().getEndDate().format(DATE_FMT) : "Hiện tại";

        Row r1 = sheet.createRow(1);
        r1.setHeightInPoints(18);
        Cell c1 = r1.createCell(0);
        c1.setCellValue("Kỳ báo cáo: Từ " + startStr + " đến " + endStr);
        c1.setCellStyle(subtitleStyle);

        sheet.createRow(2).setHeightInPoints(8);

        // Section I: Chỉ số chủ chốt
        Row rSec1 = sheet.createRow(3);
        rSec1.setHeightInPoints(22);
        Cell cSec1 = rSec1.createCell(0);
        cSec1.setCellValue("I. TỔNG QUAN CHỈ SỐ CHỦ CHỐT");
        cSec1.setCellStyle(sectionStyle);

        // Header KPI (A5:C5 merged and D5:E5 merged)
        Row kpiHeaderRow = sheet.createRow(4);
        kpiHeaderRow.setHeightInPoints(26);

        sheet.addMergedRegion(new CellRangeAddress(4, 4, 0, 2));
        Cell kpiH0 = kpiHeaderRow.createCell(0);
        kpiH0.setCellValue("Chỉ tiêu tài chính");
        kpiH0.setCellStyle(headerLeft);
        ExcelExportUtil.applyBoxBorder(sheet, 4, 0, 2, headerLeft);

        sheet.addMergedRegion(new CellRangeAddress(4, 4, 3, 4));
        Cell kpiH1 = kpiHeaderRow.createCell(3);
        kpiH1.setCellValue("Số liệu ghi nhận");
        kpiH1.setCellStyle(headerRight);
        ExcelExportUtil.applyBoxBorder(sheet, 4, 3, 4, headerRight);

        RevenueSummaryKpiDto kpi = (reportDto != null && reportDto.getKpi() != null) ? reportDto.getKpi() : new RevenueSummaryKpiDto();
        PaymentBreakdownDto payment = (reportDto != null && reportDto.getPaymentBreakdown() != null) ? reportDto.getPaymentBreakdown() : new PaymentBreakdownDto();

        BigDecimal grossRevenue = kpi.getGrossRevenue() != null ? kpi.getGrossRevenue() : BigDecimal.ZERO;
        BigDecimal netProfit = grossRevenue.multiply(BigDecimal.valueOf(0.3115)).setScale(0, RoundingMode.HALF_UP);
        BigDecimal shippingFee = kpi.getTotalShippingFee() != null ? kpi.getTotalShippingFee() : BigDecimal.ZERO;
        BigDecimal aov = kpi.getAverageOrderValue() != null ? kpi.getAverageOrderValue() : BigDecimal.ZERO;
        BigDecimal codRev = payment.getCodRevenue() != null ? payment.getCodRevenue() : BigDecimal.ZERO;
        BigDecimal vnpayRev = payment.getVnpayRevenue() != null ? payment.getVnpayRevenue() : BigDecimal.ZERO;

        Object[][] kpiItems = {
                {"Tổng doanh thu", formatNumber(grossRevenue) + " VND", true},
                {"Tổng lãi ròng", formatNumber(netProfit) + " VND", true},
                {"Số đơn hàng hoàn tất", formatNumber(kpi.getTotalOrders()) + " đơn", false},
                {"Giá trị trung bình mỗi đơn", formatNumber(aov) + " VND/đơn", false},
                {"Tổng cước vận chuyển", formatNumber(shippingFee) + " VND", false},
                {"Doanh thu qua thanh toán trực tiếp", formatNumber(codRev) + " VND", false},
                {"Doanh thu qua thanh toán điện tử", formatNumber(vnpayRev) + " VND", false}
        };

        int kpiRowIdx = 5;
        for (int i = 0; i < kpiItems.length; i++) {
            boolean isZebra = (kpiRowIdx % 2 == 1);
            boolean isBold = (Boolean) kpiItems[i][2];

            Row row = sheet.createRow(kpiRowIdx);
            row.setHeightInPoints(22);

            sheet.addMergedRegion(new CellRangeAddress(kpiRowIdx, kpiRowIdx, 0, 2));
            Cell cellTitle = row.createCell(0);
            cellTitle.setCellValue((String) kpiItems[i][0]);
            XSSFCellStyle titleCellStyle = isBold ? (isZebra ? textLeftZebraBold : textLeftWhiteBold) : (isZebra ? textLeftZebra : textLeftWhite);
            cellTitle.setCellStyle(titleCellStyle);
            ExcelExportUtil.applyBoxBorder(sheet, kpiRowIdx, 0, 2, titleCellStyle);

            sheet.addMergedRegion(new CellRangeAddress(kpiRowIdx, kpiRowIdx, 3, 4));
            Cell cellVal = row.createCell(3);
            cellVal.setCellValue((String) kpiItems[i][1]);
            XSSFCellStyle valCellStyle = isBold ? (isZebra ? textRightZebraBold : textRightWhiteBold) : (isZebra ? textRightZebra : textRightWhite);
            cellVal.setCellStyle(valCellStyle);
            ExcelExportUtil.applyBoxBorder(sheet, kpiRowIdx, 3, 4, valCellStyle);

            kpiRowIdx++;
        }

        // Section II: Bảng Doanh thu theo ngày
        int dailyStartRow = 13;
        Row rDailySec = sheet.createRow(dailyStartRow);
        rDailySec.setHeightInPoints(22);
        Cell cDailySec = rDailySec.createCell(0);
        cDailySec.setCellValue((reportDto != null && reportDto.isMonthlyGranularity()) ? "II. BẢNG DOANH THU VÀ LÃI RÒNG THEO THÁNG" : "II. BẢNG DOANH THU VÀ LÃI RÒNG THEO NGÀY");
        cDailySec.setCellStyle(sectionStyle);

        Row dailyHeader = sheet.createRow(dailyStartRow + 1);
        dailyHeader.setHeightInPoints(26);
        String periodHeader = (reportDto != null && reportDto.isMonthlyGranularity()) ? "Kỳ giao dịch (Tháng/Năm)" : "Ngày giao dịch";
        String[] dailyCols = {"STT", periodHeader, "Số đơn hoàn tất", "Doanh thu (VND)", "Lãi ròng (VND)"};

        for (int i = 0; i < dailyCols.length; i++) {
            Cell cell = dailyHeader.createCell(i);
            cell.setCellValue(dailyCols[i]);
            cell.setCellStyle(i < 2 ? headerCenter : headerRight);
        }

        int dRowIdx = dailyStartRow + 2;
        int stt = 1;
        long totalOrdersSum = 0;
        BigDecimal totalRevenueSum = BigDecimal.ZERO;
        BigDecimal totalProfitSum = BigDecimal.ZERO;

        if (reportDto != null && reportDto.getDailyTrends() != null) {
            for (DailyRevenueDto day : reportDto.getDailyTrends()) {
                boolean isZebra = (dRowIdx % 2 == 1);
                Row row = sheet.createRow(dRowIdx++);
                row.setHeightInPoints(21);

                Cell cStt = row.createCell(0);
                cStt.setCellValue(stt++);
                cStt.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);

                Cell cDate = row.createCell(1);
                if (reportDto.isMonthlyGranularity()) {
                    cDate.setCellValue(day.getDisplayLabel());
                    cDate.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);
                } else {
                    cDate.setCellValue(day.getDate() != null ? java.sql.Date.valueOf(day.getDate()) : null);
                    cDate.setCellStyle(isZebra ? dateZebra : dateWhite);
                }

                totalOrdersSum += day.getOrderCount();
                Cell cOrders = row.createCell(2);
                cOrders.setCellValue(day.getOrderCount());
                cOrders.setCellStyle(isZebra ? integerZebra : integerWhite);

                BigDecimal rev = day.getRevenue() != null ? day.getRevenue() : BigDecimal.ZERO;
                totalRevenueSum = totalRevenueSum.add(rev);
                Cell cRev = row.createCell(3);
                cRev.setCellValue(rev.doubleValue());
                cRev.setCellStyle(isZebra ? currencyZebra : currencyWhite);

                BigDecimal profit = rev.multiply(BigDecimal.valueOf(0.3115)).setScale(0, RoundingMode.HALF_UP);
                totalProfitSum = totalProfitSum.add(profit);
                Cell cProf = row.createCell(4);
                cProf.setCellValue(profit.doubleValue());
                cProf.setCellStyle(isZebra ? currencyZebra : currencyWhite);
            }
        }

        Row totalRow = sheet.createRow(dRowIdx);
        totalRow.setHeightInPoints(25);

        Cell cTot0 = totalRow.createCell(0);
        cTot0.setCellStyle(totalLabelStyle);

        Cell cTotLabel = totalRow.createCell(1);
        cTotLabel.setCellValue("TỔNG CỘNG:");
        cTotLabel.setCellStyle(totalLabelStyle);

        Cell cTotOrders = totalRow.createCell(2);
        cTotOrders.setCellValue(totalOrdersSum);
        cTotOrders.setCellStyle(totalIntegerStyle);

        Cell cTotRev = totalRow.createCell(3);
        cTotRev.setCellValue(totalRevenueSum.doubleValue());
        cTotRev.setCellStyle(totalCurrencyStyle);

        Cell cTotProf = totalRow.createCell(4);
        cTotProf.setCellValue(totalProfitSum.doubleValue());
        cTotProf.setCellStyle(totalCurrencyStyle);

        // Proportional column widths matching Template_Bao_Cao_Doanh_Thu_v2.xlsx
        ExcelExportUtil.setColumnWidths(sheet, new int[]{7, 18, 18, 20, 20});
    }

    // =========================================================================
    // 2. REVENUE REPORT: SHEET 2 (DELIVERED ORDERS DETAILS)
    // =========================================================================
    private void buildSheet2DeliveredOrders(XSSFWorkbook workbook, RevenueAnalyticsReportDto reportDto) {
        XSSFSheet sheet = workbook.createSheet("Danh sách Đơn hàng Hoàn tất");
        sheet.createFreezePane(0, 4);
        sheet.setDisplayGridlines(true);

        XSSFCellStyle titleStyle = ExcelExportUtil.createTitleStyle(workbook);
        XSSFCellStyle subtitleStyle = ExcelExportUtil.createSubtitleStyle(workbook);

        XSSFCellStyle headerCenter = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.CENTER);
        XSSFCellStyle headerLeft = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.LEFT);
        XSSFCellStyle headerRight = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.RIGHT);

        XSSFCellStyle textLeftWhite = ExcelExportUtil.createTextLeftStyle(workbook, false, false);
        XSSFCellStyle textLeftZebra = ExcelExportUtil.createTextLeftStyle(workbook, true, false);

        XSSFCellStyle textCenterWhite = ExcelExportUtil.createTextCenterStyle(workbook, false, false);
        XSSFCellStyle textCenterZebra = ExcelExportUtil.createTextCenterStyle(workbook, true, false);

        XSSFCellStyle dateWhite = ExcelExportUtil.createDateStyle(workbook, false);
        XSSFCellStyle dateZebra = ExcelExportUtil.createDateStyle(workbook, true);

        XSSFCellStyle currencyWhite = ExcelExportUtil.createCurrencyStyle(workbook, false, false);
        XSSFCellStyle currencyZebra = ExcelExportUtil.createCurrencyStyle(workbook, true, false);
        XSSFCellStyle currencyWhiteBold = ExcelExportUtil.createCurrencyStyle(workbook, false, true);
        XSSFCellStyle currencyZebraBold = ExcelExportUtil.createCurrencyStyle(workbook, true, true);

        XSSFCellStyle totalLabelStyle = ExcelExportUtil.createTotalRowLabelStyle(workbook);
        XSSFCellStyle totalCurrencyStyle = ExcelExportUtil.createTotalCurrencyStyle(workbook);

        // Row 0: Title
        Row r0 = sheet.createRow(0);
        r0.setHeightInPoints(26);
        Cell c0 = r0.createCell(0);
        c0.setCellValue("DANH SÁCH ĐƠN HÀNG HOÀN TẤT");
        c0.setCellStyle(titleStyle);

        // Row 1: Subtitle
        String startStr = (reportDto != null && reportDto.getFilter() != null && reportDto.getFilter().getStartDate() != null)
                ? reportDto.getFilter().getStartDate().format(DATE_FMT) : "Đầu kỳ";
        String endStr = (reportDto != null && reportDto.getFilter() != null && reportDto.getFilter().getEndDate() != null)
                ? reportDto.getFilter().getEndDate().format(DATE_FMT) : "Hiện tại";

        Row r1 = sheet.createRow(1);
        r1.setHeightInPoints(18);
        Cell c1 = r1.createCell(0);
        c1.setCellValue("Kỳ báo cáo: Từ " + startStr + " đến " + endStr);
        c1.setCellStyle(subtitleStyle);

        sheet.createRow(2).setHeightInPoints(8);

        String[] headers = {
                "STT", "Người nhận", "Số điện thoại", "Phương thức thanh toán",
                "Ngày đặt hàng", "Ngày giao hàng", "Cước vận chuyển (VND)",
                "Tổng tiền thanh toán (VND)", "Lãi ròng (VND)"
        };

        Row headerRow = sheet.createRow(3);
        headerRow.setHeightInPoints(26);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            if (i == 1) {
                cell.setCellStyle(headerLeft);
            } else if (i >= 6) {
                cell.setCellStyle(headerRight);
            } else {
                cell.setCellStyle(headerCenter);
            }
        }

        int rowIdx = 4;
        int stt = 1;
        BigDecimal totalShippingSum = BigDecimal.ZERO;
        BigDecimal totalAmountSum = BigDecimal.ZERO;
        BigDecimal totalProfitSum = BigDecimal.ZERO;

        List<RevenueOrderSummaryDto> exportOrders = (reportDto != null) ? reportDto.getOrdersForExport() : null;

        if (exportOrders != null && !exportOrders.isEmpty()) {
            for (RevenueOrderSummaryDto order : exportOrders) {
                boolean isZebra = (rowIdx % 2 == 1);
                Row row = sheet.createRow(rowIdx++);
                row.setHeightInPoints(22);

                Cell cStt = row.createCell(0);
                cStt.setCellValue(stt++);
                cStt.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);

                Cell cRecipient = row.createCell(1);
                cRecipient.setCellValue(order.getRecipientName() != null ? order.getRecipientName() : "");
                cRecipient.setCellStyle(isZebra ? textLeftZebra : textLeftWhite);

                Cell cPhone = row.createCell(2);
                cPhone.setCellValue(order.getPhone() != null ? order.getPhone() : "");
                cPhone.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);

                String payLabel = (order.getPaymentMethod() == Order.PaymentMethod.COD) ? "Thanh toán trực tiếp" : "Thanh toán điện tử";
                Cell cPay = row.createCell(3);
                cPay.setCellValue(payLabel);
                cPay.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);

                Cell cCreated = row.createCell(4);
                if (order.getCreatedAt() != null) {
                    cCreated.setCellValue(java.sql.Date.valueOf(order.getCreatedAt().toLocalDate()));
                }
                cCreated.setCellStyle(isZebra ? dateZebra : dateWhite);

                Cell cDelivered = row.createCell(5);
                if (order.getDeliveredAt() != null) {
                    cDelivered.setCellValue(java.sql.Date.valueOf(order.getDeliveredAt().toLocalDate()));
                }
                cDelivered.setCellStyle(isZebra ? dateZebra : dateWhite);

                BigDecimal ship = order.getShippingFee() != null ? order.getShippingFee() : BigDecimal.ZERO;
                totalShippingSum = totalShippingSum.add(ship);
                Cell cShip = row.createCell(6);
                cShip.setCellValue(ship.doubleValue());
                cShip.setCellStyle(isZebra ? currencyZebra : currencyWhite);

                BigDecimal total = order.getFinalTotalAmount() != null ? order.getFinalTotalAmount() : BigDecimal.ZERO;
                totalAmountSum = totalAmountSum.add(total);
                Cell cTotal = row.createCell(7);
                cTotal.setCellValue(total.doubleValue());
                cTotal.setCellStyle(isZebra ? currencyZebraBold : currencyWhiteBold);

                BigDecimal profit = total.subtract(ship).multiply(BigDecimal.valueOf(0.35)).setScale(0, RoundingMode.HALF_UP);
                totalProfitSum = totalProfitSum.add(profit);
                Cell cProf = row.createCell(8);
                cProf.setCellValue(profit.doubleValue());
                cProf.setCellStyle(isZebra ? currencyZebra : currencyWhite);
            }

            Row totalRow = sheet.createRow(rowIdx);
            totalRow.setHeightInPoints(25);
            for (int i = 0; i <= 5; i++) {
                Cell c = totalRow.createCell(i);
                c.setCellStyle(totalLabelStyle);
                if (i == 5) {
                    c.setCellValue("TỔNG CỘNG:");
                }
            }

            Cell cTotShip = totalRow.createCell(6);
            cTotShip.setCellValue(totalShippingSum.doubleValue());
            cTotShip.setCellStyle(totalCurrencyStyle);

            Cell cTotAmount = totalRow.createCell(7);
            cTotAmount.setCellValue(totalAmountSum.doubleValue());
            cTotAmount.setCellStyle(totalCurrencyStyle);

            Cell cTotProf = totalRow.createCell(8);
            cTotProf.setCellValue(totalProfitSum.doubleValue());
            cTotProf.setCellStyle(totalCurrencyStyle);
        } else {
            Row emptyRow = sheet.createRow(rowIdx);
            emptyRow.createCell(0).setCellValue("Không có đơn hàng hoàn tất nào trong khoảng thời gian này.");
        }

        // Proportional column widths matching Template_Bao_Cao_Doanh_Thu_v2.xlsx
        ExcelExportUtil.setColumnWidths(sheet, new int[]{6, 24, 16, 25, 16, 16, 22, 25, 20});
    }

    // =========================================================================
    // 3. STOCK REPORT: SINGLE UNIFIED SHEET (15 COLUMNS V2.1)
    // =========================================================================
    private void buildUnifiedStockMovementSheet(XSSFWorkbook workbook, StockMovementFilterRequest filter,
                                                List<StockMovementReportItemDto> items,
                                                List<StockValuationItemDto> valuationItems) {

        XSSFSheet sheet = workbook.createSheet("Biến động kho theo kỳ");
        sheet.createFreezePane(0, 4);
        sheet.setDisplayGridlines(true);

        XSSFCellStyle titleStyle = ExcelExportUtil.createTitleStyle(workbook);
        XSSFCellStyle subtitleStyle = ExcelExportUtil.createSubtitleStyle(workbook);

        XSSFCellStyle headerCenter = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.CENTER);
        XSSFCellStyle headerLeft = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.LEFT);
        XSSFCellStyle headerRight = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.RIGHT);

        XSSFCellStyle textLeftWhite = ExcelExportUtil.createTextLeftStyle(workbook, false, false);
        XSSFCellStyle textLeftZebra = ExcelExportUtil.createTextLeftStyle(workbook, true, false);

        XSSFCellStyle textCenterWhite = ExcelExportUtil.createTextCenterStyle(workbook, false, false);
        XSSFCellStyle textCenterZebra = ExcelExportUtil.createTextCenterStyle(workbook, true, false);

        XSSFCellStyle integerWhite = ExcelExportUtil.createIntegerStyle(workbook, false, false);
        XSSFCellStyle integerZebra = ExcelExportUtil.createIntegerStyle(workbook, true, false);
        XSSFCellStyle integerWhiteBold = ExcelExportUtil.createIntegerStyle(workbook, false, true);
        XSSFCellStyle integerZebraBold = ExcelExportUtil.createIntegerStyle(workbook, true, true);

        XSSFCellStyle currencyWhite = ExcelExportUtil.createCurrencyStyle(workbook, false, false);
        XSSFCellStyle currencyZebra = ExcelExportUtil.createCurrencyStyle(workbook, true, false);
        XSSFCellStyle currencyWhiteBold = ExcelExportUtil.createCurrencyStyle(workbook, false, true);
        XSSFCellStyle currencyZebraBold = ExcelExportUtil.createCurrencyStyle(workbook, true, true);

        XSSFCellStyle totalLabelStyle = ExcelExportUtil.createTotalRowLabelStyle(workbook);
        XSSFCellStyle totalCurrencyStyle = ExcelExportUtil.createTotalCurrencyStyle(workbook);
        XSSFCellStyle totalIntegerStyle = ExcelExportUtil.createTotalIntegerStyle(workbook);

        // Row 0: Title Banner
        Row r0 = sheet.createRow(0);
        r0.setHeightInPoints(26);
        Cell c0 = r0.createCell(0);
        c0.setCellValue("BIẾN ĐỘNG KHO THEO KỲ");
        c0.setCellStyle(titleStyle);

        // Row 1: Subtitle
        String startStr = (filter != null && filter.getStartDate() != null) ? filter.getStartDate().format(DATE_FMT) : "Đầu kỳ";
        String endStr = (filter != null && filter.getEndDate() != null) ? filter.getEndDate().format(DATE_FMT) : "Hiện tại";

        Row r1 = sheet.createRow(1);
        r1.setHeightInPoints(18);
        Cell c1 = r1.createCell(0);
        c1.setCellValue("Kỳ báo cáo: Từ " + startStr + " đến " + endStr);
        c1.setCellStyle(subtitleStyle);

        sheet.createRow(2).setHeightInPoints(8);

        // 15 Headers exactly matching Template_Bao_Cao_Bien_Dong_Kho_v3.xlsx
        String[] headers = {
                "STT",
                "Tên sản phẩm",
                "Phân loại",
                "Danh mục",
                "Giá nhập / SP (VND)",
                "Giá bán / SP (VND)",
                "Tồn đầu kỳ (SP)",
                "Nhập hàng (SP)",
                "Xuất hàng (SP)",
                "Hoàn hàng (SP)",
                "Tồn cuối kỳ (SP)",
                "Tiền vốn tồn đầu (VND)",
                "Tiền vốn tồn cuối (VND)",
                "Tiền bán tồn cuối (VND)",
                "Tình trạng tồn"
        };

        Row headerRow = sheet.createRow(3);
        headerRow.setHeightInPoints(28);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            if (i == 1 || i == 3) {
                cell.setCellStyle(headerLeft);
            } else if (i == 0 || i == 2 || i == 14) {
                cell.setCellStyle(headerCenter);
            } else {
                cell.setCellStyle(headerRight);
            }
        }

        // Map stock status from valuationItems if available
        Map<Long, String> valuationStatusMap = new HashMap<>();
        if (valuationItems != null) {
            for (StockValuationItemDto v : valuationItems) {
                if (v.getProductId() != null && v.getStockStatus() != null) {
                    valuationStatusMap.put(v.getProductId(), v.getStockStatus());
                }
            }
        }

        int rowIdx = 4;
        int stt = 1;
        long sumOpening = 0;
        long sumImport = 0;
        long sumDeduct = 0;
        long sumReturn = 0;
        long sumClosing = 0;
        BigDecimal sumOpeningVal = BigDecimal.ZERO;
        BigDecimal sumClosingVal = BigDecimal.ZERO;
        BigDecimal sumRetailVal = BigDecimal.ZERO;

        if (items != null) {
            for (StockMovementReportItemDto item : items) {
                boolean isZebra = (rowIdx % 2 == 1);
                Row row = sheet.createRow(rowIdx++);
                row.setHeightInPoints(22);

                // Col 0: STT
                Cell cStt = row.createCell(0);
                cStt.setCellValue(stt++);
                cStt.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);

                // Col 1: Tên sản phẩm
                Cell cTitle = row.createCell(1);
                cTitle.setCellValue(item.getTitle() != null ? item.getTitle() : "");
                cTitle.setCellStyle(isZebra ? textLeftZebra : textLeftWhite);

                // Col 2: Phân loại
                Cell cType = row.createCell(2);
                cType.setCellValue(item.getProductTypeLabel());
                cType.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);

                // Col 3: Danh mục
                Cell cCategory = row.createCell(3);
                cCategory.setCellValue(item.getCategoryName() != null ? item.getCategoryName() : "");
                cCategory.setCellStyle(isZebra ? textLeftZebra : textLeftWhite);

                // Col 4: Giá nhập / SP (VND)
                double cost = item.getOriginalCost() != null ? item.getOriginalCost().doubleValue() : 0.0;
                Cell cCost = row.createCell(4);
                cCost.setCellValue(cost);
                cCost.setCellStyle(isZebra ? currencyZebra : currencyWhite);

                // Col 5: Giá bán / SP (VND)
                double price = item.getPrice() != null ? item.getPrice().doubleValue() : 0.0;
                Cell cPrice = row.createCell(5);
                cPrice.setCellValue(price);
                cPrice.setCellStyle(isZebra ? currencyZebra : currencyWhite);

                // Col 6: Tồn đầu kỳ (SP)
                sumOpening += item.getOpeningStock();
                Cell cOpen = row.createCell(6);
                cOpen.setCellValue(item.getOpeningStock());
                cOpen.setCellStyle(isZebra ? integerZebra : integerWhite);

                // Col 7: Nhập hàng (SP)
                sumImport += item.getImportQty();
                Cell cImp = row.createCell(7);
                cImp.setCellValue(item.getImportQty());
                cImp.setCellStyle(isZebra ? integerZebra : integerWhite);

                // Col 8: Xuất hàng (SP)
                sumDeduct += item.getOrderDeductQty();
                Cell cDed = row.createCell(8);
                cDed.setCellValue(item.getOrderDeductQty());
                cDed.setCellStyle(isZebra ? integerZebra : integerWhite);

                // Col 9: Hoàn hàng (SP) = Hủy hoàn + Trả hoàn
                int totalReturn = item.getCancelRestoreQty() + item.getReturnRestoreQty();
                sumReturn += totalReturn;
                Cell cRet = row.createCell(9);
                cRet.setCellValue(totalReturn);
                cRet.setCellStyle(isZebra ? integerZebra : integerWhite);

                // Col 10: Tồn cuối kỳ (SP)
                sumClosing += item.getClosingStock();
                Cell cClose = row.createCell(10);
                cClose.setCellValue(item.getClosingStock());
                cClose.setCellStyle(isZebra ? integerZebraBold : integerWhiteBold);

                // Col 11: Tiền vốn tồn đầu (VND)
                BigDecimal openVal = item.getOpeningCostValue() != null ? item.getOpeningCostValue() : BigDecimal.ZERO;
                sumOpeningVal = sumOpeningVal.add(openVal);
                Cell cOpenVal = row.createCell(11);
                cOpenVal.setCellValue(openVal.doubleValue());
                cOpenVal.setCellStyle(isZebra ? currencyZebra : currencyWhite);

                // Col 12: Tiền vốn tồn cuối (VND)
                BigDecimal closeVal = item.getClosingCostValue() != null ? item.getClosingCostValue() : BigDecimal.ZERO;
                sumClosingVal = sumClosingVal.add(closeVal);
                Cell cCloseVal = row.createCell(12);
                cCloseVal.setCellValue(closeVal.doubleValue());
                cCloseVal.setCellStyle(isZebra ? currencyZebraBold : currencyWhiteBold);

                // Col 13: Tiền bán tồn cuối (VND)
                BigDecimal retailVal = item.getClosingRetailValue() != null ? item.getClosingRetailValue() : BigDecimal.valueOf(item.getClosingStock() * price);
                sumRetailVal = sumRetailVal.add(retailVal);
                Cell cRetailVal = row.createCell(13);
                cRetailVal.setCellValue(retailVal.doubleValue());
                cRetailVal.setCellStyle(isZebra ? currencyZebra : currencyWhite);

                // Col 14: Tình trạng tồn
                String status = valuationStatusMap.get(item.getProductId());
                if (status == null || status.isBlank()) {
                    if (!Boolean.TRUE.equals(item.getIsActive())) {
                        status = "Ngừng kinh doanh";
                    } else if (item.getClosingStock() <= 0) {
                        status = "Hết hàng";
                    } else if (item.getClosingStock() <= 10) {
                        status = "Tồn kho thấp";
                    } else {
                        status = "Tồn an toàn";
                    }
                }
                Cell cStatus = row.createCell(14);
                cStatus.setCellValue(status);
                cStatus.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);
            }
        }

        // Total Row
        Row totalRow = sheet.createRow(rowIdx);
        totalRow.setHeightInPoints(25);
        for (int i = 0; i <= 5; i++) {
            Cell c = totalRow.createCell(i);
            c.setCellStyle(totalLabelStyle);
            if (i == 5) {
                c.setCellValue("TỔNG CỘNG TOÀN KHO:");
            }
        }

        Cell cTotOpen = totalRow.createCell(6);
        cTotOpen.setCellValue(sumOpening);
        cTotOpen.setCellStyle(totalIntegerStyle);

        Cell cTotImp = totalRow.createCell(7);
        cTotImp.setCellValue(sumImport);
        cTotImp.setCellStyle(totalIntegerStyle);

        Cell cTotDed = totalRow.createCell(8);
        cTotDed.setCellValue(sumDeduct);
        cTotDed.setCellStyle(totalIntegerStyle);

        Cell cTotRet = totalRow.createCell(9);
        cTotRet.setCellValue(sumReturn);
        cTotRet.setCellStyle(totalIntegerStyle);

        Cell cTotClose = totalRow.createCell(10);
        cTotClose.setCellValue(sumClosing);
        cTotClose.setCellStyle(totalIntegerStyle);

        Cell cTotOpenVal = totalRow.createCell(11);
        cTotOpenVal.setCellValue(sumOpeningVal.doubleValue());
        cTotOpenVal.setCellStyle(totalCurrencyStyle);

        Cell cTotCloseVal = totalRow.createCell(12);
        cTotCloseVal.setCellValue(sumClosingVal.doubleValue());
        cTotCloseVal.setCellStyle(totalCurrencyStyle);

        Cell cTotRetailVal = totalRow.createCell(13);
        cTotRetailVal.setCellValue(sumRetailVal.doubleValue());
        cTotRetailVal.setCellStyle(totalCurrencyStyle);

        Cell cTotStatus = totalRow.createCell(14);
        cTotStatus.setCellStyle(totalLabelStyle);

        // Proportional column widths matching Template_Bao_Cao_Bien_Dong_Kho_v3.xlsx
        ExcelExportUtil.setColumnWidths(sheet, new int[]{7, 38, 16, 18, 18, 18, 17, 16, 16, 16, 17, 22, 22, 22, 18});
    }

    // =========================================================================
    // 4. SUPPLEMENTARY STOCK VALUATION WORKBOOK
    // =========================================================================
    private void buildValuationWorkbook(XSSFWorkbook workbook, List<StockValuationItemDto> stockItems) {
        XSSFSheet sheet = workbook.createSheet("Định giá & Tồn kho Hiện tại");
        sheet.createFreezePane(0, 4);
        sheet.setDisplayGridlines(true);

        XSSFCellStyle titleStyle = ExcelExportUtil.createTitleStyle(workbook);
        XSSFCellStyle subtitleStyle = ExcelExportUtil.createSubtitleStyle(workbook);

        XSSFCellStyle headerCenter = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.CENTER);
        XSSFCellStyle headerLeft = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.LEFT);
        XSSFCellStyle headerRight = ExcelExportUtil.createHeaderStyle(workbook, HorizontalAlignment.RIGHT);

        XSSFCellStyle textLeftWhite = ExcelExportUtil.createTextLeftStyle(workbook, false, false);
        XSSFCellStyle textLeftZebra = ExcelExportUtil.createTextLeftStyle(workbook, true, false);

        XSSFCellStyle textCenterWhite = ExcelExportUtil.createTextCenterStyle(workbook, false, false);
        XSSFCellStyle textCenterZebra = ExcelExportUtil.createTextCenterStyle(workbook, true, false);

        XSSFCellStyle integerWhite = ExcelExportUtil.createIntegerStyle(workbook, false, false);
        XSSFCellStyle integerZebra = ExcelExportUtil.createIntegerStyle(workbook, true, false);
        XSSFCellStyle integerWhiteBold = ExcelExportUtil.createIntegerStyle(workbook, false, true);
        XSSFCellStyle integerZebraBold = ExcelExportUtil.createIntegerStyle(workbook, true, true);

        XSSFCellStyle currencyWhite = ExcelExportUtil.createCurrencyStyle(workbook, false, false);
        XSSFCellStyle currencyZebra = ExcelExportUtil.createCurrencyStyle(workbook, true, false);
        XSSFCellStyle currencyWhiteBold = ExcelExportUtil.createCurrencyStyle(workbook, false, true);
        XSSFCellStyle currencyZebraBold = ExcelExportUtil.createCurrencyStyle(workbook, true, true);

        XSSFCellStyle totalLabelStyle = ExcelExportUtil.createTotalRowLabelStyle(workbook);
        XSSFCellStyle totalCurrencyStyle = ExcelExportUtil.createTotalCurrencyStyle(workbook);
        XSSFCellStyle totalIntegerStyle = ExcelExportUtil.createTotalIntegerStyle(workbook);

        Row r0 = sheet.createRow(0);
        r0.setHeightInPoints(26);
        Cell c0 = r0.createCell(0);
        c0.setCellValue("BÁO CÁO ĐỊNH GIÁ TÀI SẢN KHO HÀNG");
        c0.setCellStyle(titleStyle);

        Row r1 = sheet.createRow(1);
        r1.setHeightInPoints(18);
        Cell c1 = r1.createCell(0);
        c1.setCellValue("Thời điểm xuất: " + LocalDateTime.now().format(DATE_FMT));
        c1.setCellStyle(subtitleStyle);

        sheet.createRow(2).setHeightInPoints(8);

        String[] headers = {
                "STT", "Tên sản phẩm", "Phân loại", "Danh mục", "Trạng thái KD",
                "Số lượng tồn (SP)", "Giá nhập / SP (VND)", "Giá bán / SP (VND)",
                "Tổng giá trị vốn (VND)", "Tổng giá trị bán (VND)", "Tình trạng tồn"
        };

        Row headerRow = sheet.createRow(3);
        headerRow.setHeightInPoints(26);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            if (i == 1 || i == 3) {
                cell.setCellStyle(headerLeft);
            } else if (i == 0 || i == 2 || i == 4 || i == 10) {
                cell.setCellStyle(headerCenter);
            } else {
                cell.setCellStyle(headerRight);
            }
        }

        int rowIdx = 4;
        int stt = 1;
        long totalPhysicalUnits = 0;
        BigDecimal totalCostAsset = BigDecimal.ZERO;
        BigDecimal totalRetailAsset = BigDecimal.ZERO;

        if (stockItems != null) {
            for (StockValuationItemDto item : stockItems) {
                boolean isZebra = (rowIdx % 2 == 1);
                Row row = sheet.createRow(rowIdx++);
                row.setHeightInPoints(22);

                Cell cStt = row.createCell(0);
                cStt.setCellValue(stt++);
                cStt.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);

                Cell cTitle = row.createCell(1);
                cTitle.setCellValue(item.getTitle() != null ? item.getTitle() : "");
                cTitle.setCellStyle(isZebra ? textLeftZebra : textLeftWhite);

                Cell cType = row.createCell(2);
                cType.setCellValue(item.getProductTypeLabel());
                cType.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);

                Cell cCat = row.createCell(3);
                cCat.setCellValue(item.getCategoryName() != null ? item.getCategoryName() : "");
                cCat.setCellStyle(isZebra ? textLeftZebra : textLeftWhite);

                Cell cStatus = row.createCell(4);
                cStatus.setCellValue(item.getActiveStatusLabel());
                cStatus.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);

                int qty = item.getStockQuantity() != null ? item.getStockQuantity() : 0;
                totalPhysicalUnits += qty;
                Cell cQty = row.createCell(5);
                cQty.setCellValue(qty);
                cQty.setCellStyle(isZebra ? integerZebraBold : integerWhiteBold);

                double cost = item.getOriginalCost() != null ? item.getOriginalCost().doubleValue() : 0.0;
                Cell cCost = row.createCell(6);
                cCost.setCellValue(cost);
                cCost.setCellStyle(isZebra ? currencyZebra : currencyWhite);

                double price = item.getPrice() != null ? item.getPrice().doubleValue() : 0.0;
                Cell cPrice = row.createCell(7);
                cPrice.setCellValue(price);
                cPrice.setCellStyle(isZebra ? currencyZebra : currencyWhite);

                BigDecimal costVal = item.getTotalCostValue() != null ? item.getTotalCostValue() : BigDecimal.ZERO;
                totalCostAsset = totalCostAsset.add(costVal);
                Cell cCostVal = row.createCell(8);
                cCostVal.setCellValue(costVal.doubleValue());
                cCostVal.setCellStyle(isZebra ? currencyZebraBold : currencyWhiteBold);

                BigDecimal retailVal = item.getTotalRetailValue() != null ? item.getTotalRetailValue() : BigDecimal.ZERO;
                totalRetailAsset = totalRetailAsset.add(retailVal);
                Cell cRetailVal = row.createCell(9);
                cRetailVal.setCellValue(retailVal.doubleValue());
                cRetailVal.setCellStyle(isZebra ? currencyZebra : currencyWhite);

                Cell cStockStatus = row.createCell(10);
                cStockStatus.setCellValue(item.getStockStatus() != null ? item.getStockStatus() : "");
                cStockStatus.setCellStyle(isZebra ? textCenterZebra : textCenterWhite);
            }
        }

        Row totalRow = sheet.createRow(rowIdx);
        totalRow.setHeightInPoints(25);
        for (int i = 0; i <= 4; i++) {
            Cell c = totalRow.createCell(i);
            c.setCellStyle(totalLabelStyle);
            if (i == 4) {
                c.setCellValue("TỔNG CỘNG TÀI SẢN KHO:");
            }
        }
        Cell cTotQty = totalRow.createCell(5);
        cTotQty.setCellValue(totalPhysicalUnits);
        cTotQty.setCellStyle(totalIntegerStyle);

        totalRow.createCell(6).setCellStyle(totalLabelStyle);
        totalRow.createCell(7).setCellStyle(totalLabelStyle);

        Cell cTotCost = totalRow.createCell(8);
        cTotCost.setCellValue(totalCostAsset.doubleValue());
        cTotCost.setCellStyle(totalCurrencyStyle);

        Cell cTotRetail = totalRow.createCell(9);
        cTotRetail.setCellValue(totalRetailAsset.doubleValue());
        cTotRetail.setCellStyle(totalCurrencyStyle);

        totalRow.createCell(10).setCellStyle(totalLabelStyle);

        ExcelExportUtil.setColumnWidths(sheet, new int[]{7, 38, 16, 18, 18, 18, 18, 18, 22, 22, 18});
    }
}
