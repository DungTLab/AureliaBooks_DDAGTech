package com.ddagtech.aureliabooks.util;

import org.springframework.data.domain.Pageable;

/** FND-02. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
public final class PageableUtils {
    private PageableUtils() {}
    public static Pageable create(int page, int size, String sort) {
        // TODO FND-02: validate bounds/defaults and whitelist supported sort properties.
        throw new UnsupportedOperationException("TODO FND-02: pagination contract only");
    }
}
