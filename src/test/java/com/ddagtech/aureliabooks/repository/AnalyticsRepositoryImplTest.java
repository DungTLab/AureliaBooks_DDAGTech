package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.impl.AnalyticsRepositoryImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UC26, UC27: AnalyticsRepository Unit Tests")
class AnalyticsRepositoryImplTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @InjectMocks
    private AnalyticsRepositoryImpl repository;

    @Test
    void queryRevenueSummaryKpis_happyPath() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 21, 23, 59, 59);

        RevenueSummaryKpiDto expectedKpi = RevenueSummaryKpiDto.builder()
                .totalOrders(15)
                .grossRevenue(BigDecimal.valueOf(4500000))
                .build();

        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(expectedKpi);

        RevenueSummaryKpiDto actual = repository.queryRevenueSummaryKpis(start, end);
        assertThat(actual.getTotalOrders()).isEqualTo(15);
    }

    @Test
    void queryDailyRevenueTrends() {
        DailyRevenueDto day1 = DailyRevenueDto.builder()
                .orderCount(3)
                .revenue(BigDecimal.valueOf(600000))
                .build();

        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(day1));

        List<DailyRevenueDto> results = repository.queryDailyRevenueTrends(LocalDateTime.now(), LocalDateTime.now());
        assertThat(results).hasSize(1);
    }
}
