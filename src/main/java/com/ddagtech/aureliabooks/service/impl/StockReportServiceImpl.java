package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.service.AnalyticsService;
import com.ddagtech.aureliabooks.service.StockReportService;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One selection contract serves the stock screen and both workbook exports. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockReportServiceImpl implements StockReportService {

    private final AnalyticsService analytics;

    @Override
    public StockReportView view(StockMovementFilterRequest filter, String keyword, String category, String productType) {
        if (filter == null) filter = new StockMovementFilterRequest();
        filter.resolveDates();
        String search = clean(keyword), selectedCategory = clean(category), selectedType = clean(productType);
        List<StockMovementReportItemDto> all = analytics.getPeriodStockMovementReport(filter);
        var movements = all.stream().filter(item -> matches(item.getTitle(), item.getBarcode(), item.getCategoryName(),
                item.getProductType() == null ? "" : item.getProductType().name(), search, selectedCategory, selectedType)).toList();
        var valuations = analytics.getStockValuationReport().stream().filter(item -> matches(item.getTitle(), item.getBarcode(),
                item.getCategoryName(), item.getProductType() == null ? "" : item.getProductType().name(),
                search, selectedCategory, selectedType)).toList();
        return new StockReportView(filter, search, selectedCategory, selectedType,
                all.stream().map(StockMovementReportItemDto::getCategoryName).filter(name -> name != null && !name.isBlank()).distinct().sorted().toList(),
                movements, valuations, movements.stream().mapToLong(StockMovementReportItemDto::getOpeningStock).sum(),
                movements.stream().mapToLong(StockMovementReportItemDto::getClosingStock).sum(),
                movements.stream().map(StockMovementReportItemDto::getClosingCostValue).reduce(BigDecimal.ZERO, BigDecimal::add),
                valuations.stream().map(StockValuationItemDto::getTotalCostValue).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private boolean matches(String title, String barcode, String category, String type, String keyword, String selectedCategory, String selectedType) {
        return (keyword.isEmpty() || normalize(clean(title) + " " + clean(barcode)).contains(normalize(keyword)))
                && (selectedCategory.isEmpty() || selectedCategory.equals(category))
                && (selectedType.isEmpty() || selectedType.equals(type));
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }
}
