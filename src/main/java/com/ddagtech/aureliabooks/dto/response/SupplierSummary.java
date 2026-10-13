package com.ddagtech.aureliabooks.dto.response;

import java.time.LocalDateTime;

/**
 * Summary DTO representing a Supplier record in catalog listings (UC30).
 *
 * @param id persistent supplier identifier
 * @param name company / business name
 * @param contactName representative contact person
 * @param phone telephone number
 * @param email contact email address
 * @param address physical / warehouse address
 * @param isActive active status indicator
 * @param createdAt record registration timestamp
 * @param receiptCount number of associated goods receipts
 */
public record SupplierSummary(
        Long id,
        String name,
        String contactName,
        String phone,
        String email,
        String address,
        Boolean isActive,
        LocalDateTime createdAt,
        long receiptCount
) {
}
