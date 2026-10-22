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
        LocalDateTime endDateTime = filterRequest.getEndDateTime();

        RevenueSummaryKpiDto kpi = analyticsRepository.queryRevenueSummaryKpis(startDateTime, endDateTime);
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

        List<DailyRevenueDto> rawDaily = analyticsRepository.queryDailyRevenueTrends(startDateTime, endDateTime);
        Map<LocalDate, DailyRevenueDto> dateMap = (rawDaily != null)
                ? rawDaily.stream()
                .filter(d -> d.getDate() != null)
                .collect(Collectors.toMap(DailyRevenueDto::getDate, Function.identity(), (d1, d2) -> d1))
                : Collections.emptyMap();

        List<DailyRevenueDto> continuousDailyTrends = new ArrayList<>();
        List<String> chartDailyLabels = new ArrayList<>();
        List<BigDecimal> chartDailyRevenues = new ArrayList<>();
        List<Long> chartDailyOrderCounts = new ArrayList<>();

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
                continuousDailyTrends.add(dayDto);
                chartDailyLabels.add(dayDto.getFormattedDate());
                chartDailyRevenues.add(dayDto.getRevenue());
                chartDailyOrderCounts.add(dayDto.getOrderCount());

                current = current.plusDays(1);
            }
        }

        List<TopProductSaleDto> topBooks = analyticsRepository.queryTopSellingProducts(
                Product.ProductType.BOOK, 5, startDateTime, endDateTime);
        List<TopProductSaleDto> topStationeries = analyticsRepository.queryTopSellingProducts(
                Product.ProductType.STATIONERY, 5, startDateTime, endDateTime);

        PaymentBreakdownDto paymentBreakdown = analyticsRepository.queryPaymentMethodBreakdown(startDateTime, endDateTime);
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

        List<RevenueOrderSummaryDto> deliveredOrders = analyticsRepository.queryDeliveredOrders(
                startDateTime, endDateTime, 100);

        return RevenueAnalyticsReportDto.builder()
                .filter(filterRequest)
                .kpi(kpi)
                .dailyTrends(continuousDailyTrends)
                .topBooks(topBooks != null ? topBooks : Collections.emptyList())
                .topStationeries(topStationeries != null ? topStationeries : Collections.emptyList())
                .paymentBreakdown(paymentBreakdown)
                .recentDeliveredOrders(deliveredOrders != null ? deliveredOrders : Collections.emptyList())
                .chartDailyLabels(chartDailyLabels)
                .chartDailyRevenues(chartDailyRevenues)
                .chartDailyOrderCounts(chartDailyOrderCounts)
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
}
