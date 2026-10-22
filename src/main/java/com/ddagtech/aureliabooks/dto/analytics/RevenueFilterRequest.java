package com.ddagtech.aureliabooks.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueFilterRequest {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    private String preset;

    public void resolveDates() {
        LocalDate today = LocalDate.now();

        if (preset != null && !preset.isBlank()) {
            switch (preset.toLowerCase().trim()) {
                case "today" -> {
                    this.startDate = today;
                    this.endDate = today;
                    return;
                }
                case "last7days" -> {
                    this.startDate = today.minusDays(6);
                    this.endDate = today;
                    return;
                }
                case "thismonth" -> {
                    this.startDate = today.with(TemporalAdjusters.firstDayOfMonth());
                    this.endDate = today;
                    return;
                }
                case "lastmonth" -> {
                    LocalDate firstDayLastMonth = today.minusMonths(1).with(TemporalAdjusters.firstDayOfMonth());
                    LocalDate lastDayLastMonth = today.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
                    this.startDate = firstDayLastMonth;
                    this.endDate = lastDayLastMonth;
                    return;
                }
                default -> {}
            }
        }

        if (this.startDate == null) {
            this.startDate = today.with(TemporalAdjusters.firstDayOfMonth());
        }
        if (this.endDate == null) {
            this.endDate = today;
        }

        if (this.startDate.isAfter(this.endDate)) {
            LocalDate temp = this.startDate;
            this.startDate = this.endDate;
            this.endDate = temp;
        }

        if (this.preset == null || this.preset.isBlank()) {
            this.preset = "custom";
        }
    }

    public boolean isValidDateRange() {
        return startDate != null && endDate != null && !startDate.isAfter(endDate);
    }

    public LocalDateTime getStartDateTime() {
        return startDate != null ? startDate.atStartOfDay() : null;
    }

    public LocalDateTime getEndDateTime() {
        return endDate != null ? endDate.atTime(LocalTime.MAX) : null;
    }
}
