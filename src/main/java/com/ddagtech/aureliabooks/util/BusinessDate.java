package com.ddagtech.aureliabooks.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** The business calendar used by refund forms and their date validation. */
public final class BusinessDate {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private BusinessDate() {}

    public static LocalDate today() {
        return today(Clock.systemUTC());
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
