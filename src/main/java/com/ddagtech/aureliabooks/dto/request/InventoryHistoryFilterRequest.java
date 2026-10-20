package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary.MovementType;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Set;

/**
 * Filter request parameters for inventory movement history inspection (UC25 / SCR-DB-08).
 * Read-only criteria; supports keyword matching, movement classification, date range, and pagination.
 */
public record InventoryHistoryFilterRequest(
        String keyword,
        Long productId,
        MovementType movementType,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate startDate,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate endDate,
        Long actorUserId,
        Integer page,
        Integer size
) {
    public static final Set<Integer> ALLOWED_SIZES = Set.of(10, 20, 25, 50, 100);
    public static final int DEFAULT_SIZE = 20;
    public static final int DEFAULT_PAGE = 0;

    /**
     * Compact canonical constructor with sanitization and validation boundaries.
     */
    public InventoryHistoryFilterRequest {
        if (keyword != null) {
            keyword = keyword.trim();
            if (keyword.isEmpty()) {
                keyword = null;
            }
        }
        if (page == null || page < 0) {
            page = DEFAULT_PAGE;
        }
        if (size == null || !ALLOWED_SIZES.contains(size)) {
            size = DEFAULT_SIZE;
        }
    }

    /**
     * No-args constructor for initial default query initialization in Spring MVC forms.
     */
    public InventoryHistoryFilterRequest() {
        this(null, null, (MovementType) null, null, null, null, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    /**
     * Overloaded constructor accepting string movement classification for dynamic form bindings.
     */
    public InventoryHistoryFilterRequest(
            String keyword,
            Long productId,
            String movementTypeStr,
            LocalDate startDate,
            LocalDate endDate,
            Long actorUserId,
            Integer page,
            Integer size
    ) {
        this(
                keyword,
                productId,
                parseMovementType(movementTypeStr),
                startDate,
                endDate,
                actorUserId,
                page,
                size
        );
    }

    /**
     * Parses a string representation into a valid {@link MovementType}.
     * Maps null, empty, or "ALL" to null (unfiltered).
     *
     * @param value raw type string from request parameter or dropdown
     * @return matching MovementType, or null if empty/ALL/unrecognized
     */
    public static MovementType parseMovementType(String value) {
        if (value == null || value.trim().isEmpty() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return MovementType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Validates chronological coherence of date filter range.
     * Enforces Exception 1.0.E1 in RDS: "Start date must be earlier than or equal to End date".
     *
     * @return true if dates are empty or start date is chronologically on or before end date
     */
    public boolean isDateRangeValid() {
        if (startDate == null || endDate == null) {
            return true;
        }
        return !startDate.isAfter(endDate);
    }

    /**
     * Validates that dates are not set in the future.
     * Historical inventory movements cannot occur past today's date.
     *
     * @return true if dates are null or not after current local date
     */
    public boolean isNotFutureDate() {
        LocalDate today = LocalDate.now();
        if (startDate != null && startDate.isAfter(today)) {
            return false;
        }
        if (endDate != null && endDate.isAfter(today)) {
            return false;
        }
        return true;
    }
}
