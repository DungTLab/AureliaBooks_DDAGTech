package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.service.impl.StockReportServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StockReportServiceTest {
    private final AnalyticsService analytics = mock(AnalyticsService.class);
    private final StockReportService service = new StockReportServiceImpl(analytics);

    @Test
    void screenAndExportSelectionUsesSameAccentInsensitiveKeywordAndCategory() {
        var item = StockMovementReportItemDto.builder().productId(1L).title("Đường về nhà").barcode("ABC")
                .categoryName("Văn học").productType(Product.ProductType.BOOK).openingStock(10).closingStock(8)
                .closingCostValue(new BigDecimal("160000")).build();
        var valuation = StockValuationItemDto.builder().productId(1L).title("Đường về nhà").barcode("ABC")
                .categoryName("Văn học").productType(Product.ProductType.BOOK).stockQuantity(6)
                .totalCostValue(new BigDecimal("120000")).build();
        when(analytics.getPeriodStockMovementReport(any())).thenReturn(List.of(item));
        when(analytics.getStockValuationReport()).thenReturn(List.of(valuation));
        var report = service.view(null, "duong", "Văn học", "BOOK");
        assertThat(report.movements()).containsExactly(item);
        assertThat(report.valuations()).containsExactly(valuation);
        assertThat(report.closingQuantity()).isEqualTo(8);
        assertThat(report.closingCost()).isEqualByComparingTo("160000");
        assertThat(report.currentCost()).isEqualByComparingTo("120000");
        assertThat(service.view(null, "missing", "", "").movements()).isEmpty();
    }

    @Test
    void emptyPeriodHasZeroTotalsAndNormalizedDateRange() {
        var filter = new StockMovementFilterRequest(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 1), "custom");
        var report = service.view(filter, "", "", "");
        assertThat(report.filter().getStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(report.closingQuantity()).isZero();
        assertThat(report.currentCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.filter().getEndExclusiveDateTime()).isEqualTo(LocalDate.of(2026, 10, 21).atStartOfDay());
    }

    @Test
    void openEndedStartDate_upToEndDate_preservesNullStartAndDoesNotFlipDates() {
        LocalDate endLastMonth = LocalDate.of(2026, 10, 31);
        StockMovementFilterRequest filter = new StockMovementFilterRequest();
        filter.setEndDate(endLastMonth);

        var report = service.view(filter, "", "", "");
        assertThat(report.filter().getStartDate()).isNull();
        assertThat(report.filter().getEndDate()).isEqualTo(endLastMonth);
        assertThat(report.filter().getStartDateTime()).isNull();
        assertThat(report.filter().getEndExclusiveDateTime()).isEqualTo(LocalDate.of(2026, 11, 1).atStartOfDay());
        assertThat(report.filter().isValidDateRange()).isTrue();
    }

    @Test
    void openEndedEndDate_fromStartDate_preservesNullEnd() {
        LocalDate startMonth = LocalDate.of(2026, 10, 1);
        StockMovementFilterRequest filter = new StockMovementFilterRequest();
        filter.setStartDate(startMonth);

        var report = service.view(filter, "", "", "");
        assertThat(report.filter().getStartDate()).isEqualTo(startMonth);
        assertThat(report.filter().getEndDate()).isNull();
        assertThat(report.filter().getStartDateTime()).isEqualTo(startMonth.atStartOfDay());
        assertThat(report.filter().getEndExclusiveDateTime()).isNull();
        assertThat(report.filter().isValidDateRange()).isTrue();
    }

    @Test
    void bothNullDates_defaultsToThisMonth() {
        StockMovementFilterRequest filter = new StockMovementFilterRequest();
        filter.resolveDates();
        LocalDate today = LocalDate.now();
        assertThat(filter.getStartDate()).isEqualTo(today.with(java.time.temporal.TemporalAdjusters.firstDayOfMonth()));
        assertThat(filter.getEndDate()).isEqualTo(today);
        assertThat(filter.getPreset()).isEqualTo("thismonth");
        assertThat(filter.isValidDateRange()).isTrue();
    }

    @Test
    void presetAll_leavesDatesNull_forUnboundedHistoryExport() {
        StockMovementFilterRequest filter = StockMovementFilterRequest.builder().preset("all").build();
        filter.resolveDates();
        assertThat(filter.getStartDate()).isNull();
        assertThat(filter.getEndDate()).isNull();
        assertThat(filter.getStartDateTime()).isNull();
        assertThat(filter.getEndExclusiveDateTime()).isNull();
        assertThat(filter.isValidDateRange()).isTrue();
    }

    @Test
    void searchByReceiptReferenceCode_matchesProductMovements() {
        var itemReceipt = StockMovementReportItemDto.builder().productId(10L).title("Dế Mèn Phiêu Lưu Ký").barcode("8930001")
                .referenceCodes("PN-202610-001 ORD-202610-005").categoryName("Văn học").productType(Product.ProductType.BOOK)
                .openingStock(5).closingStock(15).closingCostValue(new BigDecimal("300000")).build();
        var itemOther = StockMovementReportItemDto.builder().productId(20L).title("Bút Dạ Quang").barcode("8930002")
                .referenceCodes("PN-202610-002").categoryName("Văn phòng phẩm").productType(Product.ProductType.STATIONERY)
                .openingStock(10).closingStock(20).closingCostValue(new BigDecimal("100000")).build();

        when(analytics.getPeriodStockMovementReport(any())).thenReturn(List.of(itemReceipt, itemOther));
        when(analytics.getStockValuationReport()).thenReturn(List.of());

        var report = service.view(null, "PN-202610-001", "", "");
        assertThat(report.movements()).containsExactly(itemReceipt);
        assertThat(report.closingQuantity()).isEqualTo(15);
    }

    @Test
    void searchByIsbn_withOrWithoutHyphens_matches() {
        var book = StockMovementReportItemDto.builder().productId(15L).title("Clean Architecture").barcode("8939999")
                .isbn("978-604-01-2345-6").categoryName("Công nghệ").productType(Product.ProductType.BOOK)
                .openingStock(2).closingStock(7).closingCostValue(new BigDecimal("700000")).build();
        var valuation = StockValuationItemDto.builder().productId(15L).title("Clean Architecture").barcode("8939999")
                .isbn("978-604-01-2345-6").categoryName("Công nghệ").productType(Product.ProductType.BOOK)
                .stockQuantity(7).totalCostValue(new BigDecimal("700000")).build();

        when(analytics.getPeriodStockMovementReport(any())).thenReturn(List.of(book));
        when(analytics.getStockValuationReport()).thenReturn(List.of(valuation));

        // Search without hyphens
        var reportCompact = service.view(null, "9786040123456", "", "");
        assertThat(reportCompact.movements()).containsExactly(book);
        assertThat(reportCompact.valuations()).containsExactly(valuation);

        // Search with hyphens
        var reportHyphen = service.view(null, "978-604-01", "", "");
        assertThat(reportHyphen.movements()).containsExactly(book);
    }
}
