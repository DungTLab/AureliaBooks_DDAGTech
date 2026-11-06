package com.ddagtech.aureliabooks.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Utility helper for Apache POI Excel export operations.
 * Implements the executive, clean business template design system:
 * - 100% black text (#000000)
 * - Soft slate header (#E2E8F0)
 * - Subtle zebra striping (#F8FAFC)
 * - Accounting double-line bottom border on totals (#F1F5F9)
 * - Proportional column widths
 */
public final class ExcelExportUtil {

    public static final byte[] HEADER_LIGHT_SLATE_RGB = new byte[]{(byte) 226, (byte) 232, (byte) 240};
    public static final byte[] ZEBRA_RGB = new byte[]{(byte) 248, (byte) 250, (byte) 252};
    public static final byte[] TOTAL_RGB = new byte[]{(byte) 241, (byte) 245, (byte) 249};

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
        XSSFFont font = createFont(wb, "Segoe UI", 15, true, false, IndexedColors.BLACK);
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

    public static XSSFCellStyle createSectionStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 11, true, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    public static XSSFCellStyle createHeaderStyle(XSSFWorkbook wb, HorizontalAlignment align) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, true, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(align != null ? align : HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);

        style.setFillForegroundColor(new XSSFColor(HEADER_LIGHT_SLATE_RGB, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createHeaderStyle(XSSFWorkbook wb, byte[] rgbBackground) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, true, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);

        if (rgbBackground != null) {
            style.setFillForegroundColor(new XSSFColor(rgbBackground, null));
        } else {
            style.setFillForegroundColor(new XSSFColor(HEADER_LIGHT_SLATE_RGB, null));
        }
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createCurrencyStyle(XSSFWorkbook wb) {
        return createCurrencyStyle(wb, false, false);
    }

    public static XSSFCellStyle createCurrencyStyle(XSSFWorkbook wb, boolean isZebra, boolean isBold) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, isBold, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0"));

        if (isZebra) {
            style.setFillForegroundColor(new XSSFColor(ZEBRA_RGB, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createIntegerStyle(XSSFWorkbook wb) {
        return createIntegerStyle(wb, false, false);
    }

    public static XSSFCellStyle createIntegerStyle(XSSFWorkbook wb, boolean isZebra, boolean isBold) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, isBold, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0"));

        if (isZebra) {
            style.setFillForegroundColor(new XSSFColor(ZEBRA_RGB, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createPercentStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("0.0%"));
        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createDateStyle(XSSFWorkbook wb) {
        return createDateStyle(wb, false);
    }

    public static XSSFCellStyle createDateStyle(XSSFWorkbook wb, boolean isZebra) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("dd/mm/yyyy"));

        if (isZebra) {
            style.setFillForegroundColor(new XSSFColor(ZEBRA_RGB, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createDateTimeStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, false, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("dd/mm/yyyy"));
        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createTextLeftStyle(XSSFWorkbook wb) {
        return createTextLeftStyle(wb, false, false);
    }

    public static XSSFCellStyle createTextLeftStyle(XSSFWorkbook wb, boolean isZebra, boolean isBold) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, isBold, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        if (isZebra) {
            style.setFillForegroundColor(new XSSFColor(ZEBRA_RGB, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createTextCenterStyle(XSSFWorkbook wb) {
        return createTextCenterStyle(wb, false, false);
    }

    public static XSSFCellStyle createTextCenterStyle(XSSFWorkbook wb, boolean isZebra, boolean isBold) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, isBold, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        if (isZebra) {
            style.setFillForegroundColor(new XSSFColor(ZEBRA_RGB, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        setThinBorders(style);
        return style;
    }

    public static XSSFCellStyle createTotalRowLabelStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, true, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        style.setFillForegroundColor(new XSSFColor(TOTAL_RGB, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.DOUBLE);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    public static XSSFCellStyle createTotalCurrencyStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, true, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0"));

        style.setFillForegroundColor(new XSSFColor(TOTAL_RGB, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.DOUBLE);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    public static XSSFCellStyle createTotalIntegerStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = createFont(wb, "Segoe UI", 10, true, false, IndexedColors.BLACK);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);

        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0"));

        style.setFillForegroundColor(new XSSFColor(TOTAL_RGB, null));
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

    public static void setColumnWidths(Sheet sheet, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
    }

    public static void applyBoxBorder(Sheet sheet, int rowIdx, int colStart, int colEnd, CellStyle style) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) {
            row = sheet.createRow(rowIdx);
        }
        for (int c = colStart; c <= colEnd; c++) {
            Cell cell = row.getCell(c);
            if (cell == null) {
                cell = row.createCell(c);
            }
            cell.setCellStyle(style);
        }
    }

    public static void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
            int currentWidth = sheet.getColumnWidth(i);
            sheet.setColumnWidth(i, Math.min(currentWidth + 1200, 256 * 60));
        }
    }
}
