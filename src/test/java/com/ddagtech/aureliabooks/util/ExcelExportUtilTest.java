package com.ddagtech.aureliabooks.util;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.service.impl.ExcelExportServiceImpl;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UC27: Apache POI Excel Export Engine Unit Tests")
class ExcelExportUtilTest {

    private final ExcelExportServiceImpl excelExportService = new ExcelExportServiceImpl();

    @Test
    void exportRevenueExcel_happyPath() throws IOException {
        RevenueAnalyticsReportDto reportDto = new RevenueAnalyticsReportDto();
        reportDto.getKpi().setTotalOrders(1);
        reportDto.getKpi().setGrossRevenue(BigDecimal.valueOf(100000));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        excelExportService.exportRevenueExcel(reportDto, baos);

        byte[] bytes = baos.toByteArray();
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook parsedWb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(parsedWb.getNumberOfSheets()).isEqualTo(3);
        }
    }
}
