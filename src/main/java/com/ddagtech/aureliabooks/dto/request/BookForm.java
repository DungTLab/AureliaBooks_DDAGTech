package com.ddagtech.aureliabooks.dto.request;

import java.math.BigDecimal;
import java.util.Set;
import com.ddagtech.aureliabooks.entity.Book;

/** UC15: stock quantity is updated via receipt workflow. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
public record BookForm(
        String barcode,
        String title,
        Long categoryId,
        BigDecimal price,
        BigDecimal originalCost,
        Integer weightGrams,
        String mainImageUrl,
        String tags,
        String description,
        String isbn,
        Long publisherId,
        Set<Long> authorIds,
        String seriesName,
        Integer volumeNumber,
        Boolean isTextbook,
        Integer publicationYear,
        String edition,
        Integer pageCount,
        String language,
        Book.CoverType coverType) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
