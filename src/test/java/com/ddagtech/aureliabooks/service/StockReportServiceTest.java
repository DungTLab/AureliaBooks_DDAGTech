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
}
