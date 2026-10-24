package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.AnalyticsRepository;
import com.ddagtech.aureliabooks.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsServiceImpl implements AnalyticsService {

    private final AnalyticsRepository analyticsRepository;

    @Override
    public RevenueAnalyticsReportDto getRevenueReport(RevenueFilterRequest filterRequest) {
        if (filterRequest == null) {
            filterRequest = new RevenueFilterRequest();
        }
        filterRequest.resolveDates();

        LocalDateTime startDateTime = filterRequest.getStartDateTime();
        LocalDateTime endExclusiveDateTime = filterRequest.getEndExclusiveDateTime();

        RevenueSummaryKpiDto kpi = analyticsRepository.queryRevenueSummaryKpis(startDateTime, endExclusiveDateTime);
        if (kpi == null) {
            kpi = new RevenueSummaryKpiDto();
        }
        if (kpi.getTotalOrders() > 0 && kpi.getGrossRevenue() != null) {
            BigDecimal computedAov = kpi.getGrossRevenue()
                    .divide(BigDecimal.valueOf(kpi.getTotalOrders()), 2, RoundingMode.HALF_UP);
            kpi.setAverageOrderValue(computedAov);
        } else {
            kpi.setAverageOrderValue(BigDecimal.ZERO);
        }

        List<DailyRevenueDto> continuousTrends = new ArrayList<>();
        List<String> chartLabels = new ArrayList<>();
        List<BigDecimal> chartRevenues = new ArrayList<>();
        List<Long> chartOrderCounts = new ArrayList<>();

        if (filterRequest.isMonthlyGranularity()) {
            // F06: Monthly trend aggregation with zero-filling across year boundaries
            List<DailyRevenueDto> rawMonthly = analyticsRepository.queryMonthlyRevenueTrends(startDateTime, endExclusiveDateTime);
            Map<YearMonth, DailyRevenueDto> monthMap = new HashMap<>();
            if (rawMonthly != null) {
                for (DailyRevenueDto dto : rawMonthly) {
                    if (dto.getDate() != null) {
                        monthMap.put(YearMonth.from(dto.getDate()), dto);
                    }
                }
            }

            LocalDate start = filterRequest.getStartDate();
            LocalDate end = filterRequest.getEndDate();
            if (start != null && end != null) {
                YearMonth currentYm = YearMonth.from(start);
                YearMonth endYm = YearMonth.from(end);

                while (!currentYm.isAfter(endYm)) {
                    DailyRevenueDto monthDto = monthMap.get(currentYm);
                    if (monthDto == null) {
                        String label = String.format("%02d/%d", currentYm.getMonthValue(), currentYm.getYear());
                        monthDto = DailyRevenueDto.builder()
                                .date(currentYm.atDay(1))
                                .periodLabel(label)
                                .orderCount(0L)
                                .revenue(BigDecimal.ZERO)
                                .build();
                    } else if (monthDto.getPeriodLabel() == null || monthDto.getPeriodLabel().isBlank()) {
                        monthDto.setPeriodLabel(String.format("%02d/%d", currentYm.getMonthValue(), currentYm.getYear()));
                    }

                    continuousTrends.add(monthDto);
                    chartLabels.add(monthDto.getDisplayLabel());
                    chartRevenues.add(monthDto.getRevenue());
                    chartOrderCounts.add(monthDto.getOrderCount());

                    currentYm = currentYm.plusMonths(1);
                }
            }
        } else {
            // Daily trend aggregation with zero-filling
            List<DailyRevenueDto> rawDaily = analyticsRepository.queryDailyRevenueTrends(startDateTime, endExclusiveDateTime);
            Map<LocalDate, DailyRevenueDto> dateMap = (rawDaily != null)
                    ? rawDaily.stream()
                    .filter(d -> d.getDate() != null)
                    .collect(Collectors.toMap(DailyRevenueDto::getDate, Function.identity(), (d1, d2) -> d1))
                    : Collections.emptyMap();

            LocalDate current = filterRequest.getStartDate();
            LocalDate end = filterRequest.getEndDate();

            if (current != null && end != null) {
                while (!current.isAfter(end)) {
                    DailyRevenueDto dayDto = dateMap.get(current);
                    if (dayDto == null) {
                        dayDto = DailyRevenueDto.builder()
                                .date(current)
                                .orderCount(0L)
                                .revenue(BigDecimal.ZERO)
                                .build();
                    }
                    continuousTrends.add(dayDto);
                    chartLabels.add(dayDto.getFormattedDate());
                    chartRevenues.add(dayDto.getRevenue());
                    chartOrderCounts.add(dayDto.getOrderCount());

                    current = current.plusDays(1);
                }
            }
        }

        List<TopProductSaleDto> topBooks = analyticsRepository.queryTopSellingProducts(
                Product.ProductType.BOOK, 5, startDateTime, endExclusiveDateTime);
        List<TopProductSaleDto> topStationeries = analyticsRepository.queryTopSellingProducts(
                Product.ProductType.STATIONERY, 5, startDateTime, endExclusiveDateTime);

        PaymentBreakdownDto paymentBreakdown = analyticsRepository.queryPaymentMethodBreakdown(startDateTime, endExclusiveDateTime);
        if (paymentBreakdown == null) {
            paymentBreakdown = new PaymentBreakdownDto();
        }

        List<String> chartPaymentLabels = List.of("COD (Tiền mặt)", "VNPAY (Điện tử)");
        List<BigDecimal> chartPaymentValues = List.of(
                paymentBreakdown.getCodRevenue() != null ? paymentBreakdown.getCodRevenue() : BigDecimal.ZERO,
                paymentBreakdown.getVnpayRevenue() != null ? paymentBreakdown.getVnpayRevenue() : BigDecimal.ZERO
        );
        List<Long> chartPaymentCounts = List.of(
                paymentBreakdown.getCodOrdersCount(),
                paymentBreakdown.getVnpayOrdersCount()
        );

        // Recent 100 orders for UI fast preview
        List<RevenueOrderSummaryDto> recentOrders = analyticsRepository.queryDeliveredOrders(
                startDateTime, endExclusiveDateTime, 100);

        // Full orders list for complete Excel export (F03)
        List<RevenueOrderSummaryDto> allOrders = analyticsRepository.queryAllDeliveredOrders(
                startDateTime, endExclusiveDateTime);

        return RevenueAnalyticsReportDto.builder()
                .filter(filterRequest)
                .kpi(kpi)
                .dailyTrends(continuousTrends)
                .topBooks(topBooks != null ? topBooks : Collections.emptyList())
                .topStationeries(topStationeries != null ? topStationeries : Collections.emptyList())
                .paymentBreakdown(paymentBreakdown)
                .recentDeliveredOrders(recentOrders != null ? recentOrders : Collections.emptyList())
                .allDeliveredOrders(allOrders != null ? allOrders : Collections.emptyList())
                .chartDailyLabels(chartLabels)
                .chartDailyRevenues(chartRevenues)
                .chartDailyOrderCounts(chartOrderCounts)
                .chartPaymentLabels(chartPaymentLabels)
                .chartPaymentValues(chartPaymentValues)
                .chartPaymentCounts(chartPaymentCounts)
                .build();
    }

    @Override
    public List<StockValuationItemDto> getStockValuationReport() {
        List<StockValuationItemDto> items = analyticsRepository.queryStockValuationReport();
        return items != null ? items : Collections.emptyList();
    }

    @Override
    public List<StockMovementReportItemDto> getPeriodStockMovementReport(StockMovementFilterRequest filterRequest) {
        if (filterRequest == null) {
            filterRequest = new StockMovementFilterRequest();
        }
        filterRequest.resolveDates();

        LocalDateTime fromInclusive = filterRequest.getStartDateTime();
        LocalDateTime toExclusive = filterRequest.getEndExclusiveDateTime();

        List<StockMovementReportItemDto> items = analyticsRepository.queryPeriodStockMovementReport(fromInclusive, toExclusive);
        return items != null ? items : Collections.emptyList();
    }
}
