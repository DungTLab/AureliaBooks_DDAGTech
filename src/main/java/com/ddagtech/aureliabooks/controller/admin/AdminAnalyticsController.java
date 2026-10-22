package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.analytics.RevenueAnalyticsReportDto;
import com.ddagtech.aureliabooks.dto.analytics.RevenueFilterRequest;
import com.ddagtech.aureliabooks.dto.analytics.StockValuationItemDto;
import com.ddagtech.aureliabooks.service.AnalyticsService;
import com.ddagtech.aureliabooks.service.ExcelExportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Controller
@RequestMapping({"/admin/analytics", "/manager/analytics"})
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final AnalyticsService analyticsService;
    private final ExcelExportService excelExportService;

    private static final String EXCEL_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @GetMapping({"", "/", "/revenue"})
    public String viewRevenueDashboard(@ModelAttribute RevenueFilterRequest filter, Model model) {
        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);
        model.addAttribute("report", report);
        model.addAttribute("filter", report.getFilter());
        return "admin/analytics/revenue-report";
    }

    @GetMapping("/export/revenue")
    public void exportRevenueExcel(@ModelAttribute RevenueFilterRequest filter, HttpServletResponse response) throws IOException {
        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);

        String startStr = report.getFilter().getStartDate() != null ? report.getFilter().getStartDate().format(FILE_DATE_FMT) : "all";
        String endStr = report.getFilter().getEndDate() != null ? report.getFilter().getEndDate().format(FILE_DATE_FMT) : "all";
        String filename = "Bao_Cao_Doanh_Thu_" + startStr + "_" + endStr + ".xlsx";

        response.setContentType(EXCEL_MIME_TYPE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, 0);

        excelExportService.exportRevenueExcel(report, response.getOutputStream());
    }

    @GetMapping("/export/stock")
    public void exportStockValuationExcel(HttpServletResponse response) throws IOException {
        List<StockValuationItemDto> stockItems = analyticsService.getStockValuationReport();

        String todayStr = LocalDate.now().format(FILE_DATE_FMT);
        String filename = "Bao_Cao_Ton_Kho_Dinh_Gia_" + todayStr + ".xlsx";

        response.setContentType(EXCEL_MIME_TYPE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");

        excelExportService.exportStockValuationExcel(stockItems, response.getOutputStream());
    }

    @GetMapping("/api/revenue-data")
    @ResponseBody
    public ResponseEntity<RevenueAnalyticsReportDto> getRevenueDataApi(@ModelAttribute RevenueFilterRequest filter) {
        RevenueAnalyticsReportDto report = analyticsService.getRevenueReport(filter);
        return ResponseEntity.ok(report);
    }
}
