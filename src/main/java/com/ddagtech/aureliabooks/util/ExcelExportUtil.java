package com.ddagtech.aureliabooks.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ExcelExportUtil {

    private ExcelExportUtil() {}

    public static XSSFFont createFont(XSSFWorkbook wb, String name, int sizePt, boolean bold, boolean italic, IndexedColors color) {
        XSSFFont font = wb.createFont();
        font.setFontName(name);
        font.setFontHeightInPoints((short) sizePt);
        font.setBold(bold);
        font.setItalic(italic);
        if (color != null) {
            font.setColor(color.getIndex());
        }
        return font;
    }

    public static XSSFCellStyle createTitleStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 16, true, false, IndexedColors.DARK_BLUE);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    public static XSSFCellStyle createSubtitleStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, true, IndexedColors.GREY_50_PERCENT);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    public static XSSFCellStyle createHeaderStyle(XSSFWorkbook wb, byte[] rgbBackground) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 11, true, false, IndexedColors.WHITE);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);

        if (rgbBackground != null) {
            style.setFillForegroundColor(new XSSFColor(rgbBackground, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        } else {
            style.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createCurrencyStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, false, null);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0 \"₫\""));
        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createIntegerStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, false, null);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0"));
        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createPercentStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, false, null);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("0.0%"));
        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createDateStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, false, null);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("dd/mm/yyyy"));
        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createDateTimeStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, false, null);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("dd/mm/yyyy hh:mm"));
        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createTextLeftStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, false, null);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createTextCenterStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, false, null);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createTotalRowLabelStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 11, true, false, null);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.DOUBLE);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    public static XSSFCellStyle createTotalCurrencyStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 11, true, false, null);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0 \"₫\""));

        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.DOUBLE);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    public static XSSFCellStyle createTotalIntegerStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 11, true, false, null);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0"));

        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.DOUBLE);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    public static void setThinBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    public static void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
            int currentWidth = sheet.getColumnWidth(i);
            sheet.setColumnWidth(i, Math.min(currentWidth + 1200, 256 * 60));
        }
    }
}
