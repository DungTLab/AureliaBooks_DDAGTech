package com.ddagtech.aureliabooks.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Locale;

/**
 * FND-02. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending.
 */
public final class PageableUtils {

    public static final int DEFAULT_PAGE_NUMBER = 0;
    public static final int DEFAULT_PAGE_SIZE = 12;
    public static final int MAX_PAGE_SIZE = 100;

    public static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private PageableUtils() {
    }

    public static Pageable create(int page, int size, String sort) {
        return create(Integer.valueOf(page), Integer.valueOf(size), sort);
    }

    public static Pageable create(Integer page, Integer size, String sort) {
        int targetPage = (page == null || page < 1) ? DEFAULT_PAGE_NUMBER : page - 1;
        int targetSize;
        if (size == null || size <= 0) {
            targetSize = DEFAULT_PAGE_SIZE;
        } else {
            targetSize = Math.min(size, MAX_PAGE_SIZE);
        }
        Sort targetSort = resolveSort(sort);
        return PageRequest.of(targetPage, targetSize, targetSort);
    }

    public static Sort resolveSort(String sortKey) {
        if (sortKey == null || sortKey.isBlank()) {
            return DEFAULT_SORT;
        }

        String normalizedKey = sortKey.trim().toLowerCase();

        return switch (normalizedKey) {
            case "price_asc", "price-asc" ->
                    Sort.by(Sort.Direction.ASC, "price").and(Sort.by(Sort.Direction.DESC, "id"));
            case "price_desc", "price-desc" ->
                    Sort.by(Sort.Direction.DESC, "price").and(Sort.by(Sort.Direction.DESC, "id"));
            case "create_at_desc", "newest" -> DEFAULT_SORT;
            case "name_asc", "title_asc", "alpla_asc" ->
                    Sort.by(Sort.Direction.ASC, "title").and(Sort.by(Sort.Direction.DESC, "id"));
            default -> DEFAULT_SORT;
        };
    }
}
